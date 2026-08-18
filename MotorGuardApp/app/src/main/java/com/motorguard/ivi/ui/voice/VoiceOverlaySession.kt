package com.motorguard.ivi.ui.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.motorguard.ivi.MainActivity
import com.motorguard.ivi.ui.theme.MotorGuardTheme
import com.motorguard.ivi.ui.dialer.PhoneVoice

/**
 * One voice interaction, start to finish.
 *
 * Flow (docs/07-voice.md):
 *   wake word / mic button → onShow → AudioFocus → LISTENING
 *   silence 1.5 s          → THINKING → VoiceEngine (C++ core)
 *   reply                  → SPEAKING (Android TTS) → auto-dismiss after 1 s
 *
 * The session owns no reasoning. It captures speech, hands the text to
 * [VoiceEngine], and speaks whatever comes back.
 */
class VoiceOverlaySession(context: Context) : VoiceInteractionSession(context) {

    private companion object {
        const val TAG = "MotorGuardVoice"
        const val SILENCE_MS = 1_500L
        const val DISMISS_DELAY_MS = 1_000L
        const val UTTERANCE_ID = "motorguard-reply"

        /**
         * Floor on how long the overlay stays up, measured from onShow(). Exists because
         * onCreateContentView()'s window is stood up by the platform asynchronously, and
         * on a cold process that can take longer than the old DISMISS_DELAY_MS alone gave
         * it: a fast fail() (recognizer not ready yet) or a quick false-positive "heard
         * speech" from ambient noise could call hide() before the window ever drew a
         * first frame, tearing the Compose content down before anyone saw it. Every path
         * that can end the session routes through requestHide() so none of them can race
         * ahead of this floor.
         */
        const val MIN_VISIBLE_MS = 1_800L
    }

    private var model by mutableStateOf(VoiceUiModel())

    /**
     * STT input language only -- read fresh on every show, so a language
     * change in Settings takes effect next turn. Output (replies, TTS) is
     * always English; see VoiceOverlayStrings and the fixed English passed to
     * VoiceEngine.handle() below.
     */
    private var language = VoiceLanguage.DEFAULT

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private var overlayHost: OverlayHost? = null
    private var shownAtElapsedMs = 0L

    // --- view --------------------------------------------------------------

    override fun onCreateContentView(): View {
        val host = OverlayHost().also { overlayHost = it }
        return ComposeView(context).apply {
            // A Service-hosted window has no ViewTree owners of its own; Compose
            // needs them, so the session supplies a minimal lifecycle host.
            host.attachTo(this)
            setContent {
                MotorGuardTheme(dark = true) {
                    VoiceOverlay(
                        model = model,
                        onChip = ::route,
                        onDismiss = ::requestHide,
                    )
                }
            }
        }
    }

    // --- lifecycle ---------------------------------------------------------

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shownAtElapsedMs = SystemClock.elapsedRealtime()
        language = VoicePrefs.getLanguage(context)
        WakeTone.play(context)
        VoiceEngine.ensureReady()
        overlayHost?.resume()
        requestFocus()
        ensureTts()
        model = VoiceUiModel(state = VoiceState.LISTENING)
        startListening()
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        runCatching { tts?.stop() }
        abandonFocus()
        // Paused, not destroyed: the platform reuses this same session object across
        // repeated show/hide cycles rather than minting a new one each time, and
        // onCreateContentView() is only ever called once per session object. Destroying
        // the host's lifecycle here left it permanently DESTROYED — a terminal state —
        // so every show after the first found a dead lifecycle and Compose silently
        // declined to recompose into it: the overlay would work exactly once per
        // process and then never draw again. The real teardown now happens in
        // onDestroy(), which fires when the platform is actually done with this session.
        overlayHost?.pause()
        model = VoiceUiModel()
        super.onHide()
    }

    override fun onDestroy() {
        overlayHost?.destroy()
        overlayHost = null
        super.onDestroy()
    }

    // --- speech in ---------------------------------------------------------

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // Offline STT (Whisper/Vosk) is the planned replacement — see
            // docs/07-voice-implementation.md.
            fail(VoiceOverlayStrings.recognitionUnavailable)
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    model = model.copy(state = VoiceState.LISTENING)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // rmsdB is roughly -2..10; map to 0..1 for the waveform.
                    model = model.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    firstResult(partialResults)?.let {
                        model = model.copy(transcript = it)
                    }
                }

                override fun onEndOfSpeech() {
                    model = model.copy(state = VoiceState.THINKING, level = 0f)
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results)
                    if (text.isNullOrBlank()) {
                        fail(VoiceOverlayStrings.didNotCatch)
                    } else {
                        Log.i(TAG, "heard: $text")
                        model = model.copy(state = VoiceState.THINKING, transcript = text)
                        answer(text)
                    }
                }

                override fun onError(error: Int) {
                    Log.i(TAG, "recognizer error $error")
                    fail(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                VoiceOverlayStrings.didNotHear
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                VoiceOverlayStrings.needsMicPermission
                            else -> VoiceOverlayStrings.recognitionFailed
                        }
                    )
                }

                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { fail(VoiceOverlayStrings.couldNotStartListening) }
    }

    private fun stopListening() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // --- reasoning + speech out -------------------------------------------

    /** Hands the utterance to the C++ core and speaks the reply. */
    /**
    * Phone intents first (they need the contact list, which the core doesn't have),
    * then the C++ core. Either way the reply is spoken.
    */
    private fun answer(utterance: String) {
        PhoneVoice.handle(context, utterance)?.let { reply ->
            model = model.copy(state = VoiceState.SPEAKING, reply = reply)
            speak(reply)
            return
        }
        // Always English: the reasoning core replies in whatever language was
        // last set, and Egyptian Arabic input should not flip replies to
        // Arabic (see VoiceLanguage's doc comment).
        val reply = VoiceEngine.handle(utterance, VoiceLanguage.ENGLISH) ?: VoiceOverlayStrings.fallbackReply
        Log.i(TAG, "reply: $reply")
        model = model.copy(state = VoiceState.SPEAKING, reply = reply)
        speak(reply)
    }

    private fun fail(message: String) {
        stopListening()
        model = model.copy(state = VoiceState.SPEAKING, reply = message, level = 0f)
        speak(message)
    }

    private fun ensureTts() {
        // Always English -- see the comment on `language` above. Set once;
        // this never needs re-applying on a language change since it never
        // changes.
        val existing = tts
        if (existing != null) {
            existing.language = VoiceLanguage.ENGLISH.locale
            return
        }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = VoiceLanguage.ENGLISH.locale
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onDone(utteranceId: String?) = scheduleDismiss()
                    override fun onError(utteranceId: String?) = scheduleDismiss()
                    override fun onStart(utteranceId: String?) {}
                })
            } else {
                Log.w(TAG, "TTS unavailable; overlay will show text only")
            }
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null) { scheduleDismiss(); return }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        // Belt and braces: if the callback never lands, don't leave the bar up.
        handler.postDelayed({ if (model.state == VoiceState.SPEAKING) scheduleDismiss() },
            20_000L)
    }

    /** Auto-dismiss 1 s after the reply finishes (docs/07-voice.md). */
    private fun scheduleDismiss() {
        handler.postDelayed(::requestHide, DISMISS_DELAY_MS)
    }

    /** Every path that can end the session calls this instead of hide() directly. */
    private fun requestHide() {
        val remaining = MIN_VISIBLE_MS - (SystemClock.elapsedRealtime() - shownAtElapsedMs)
        if (remaining > 0) handler.postDelayed(::hide, remaining) else hide()
    }

    // --- routing -----------------------------------------------------------

    /** Quick-action chip → bring the right tab forward, then dismiss. */
    private fun route(target: VoiceRoute) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TAB, target.name)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "could not route to ${target.name}", it) }
        requestHide()
    }

    // --- audio focus -------------------------------------------------------

    private fun requestFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
            .also { am.requestAudioFocus(it) }
    }

    private fun abandonFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        focusRequest?.let { am?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}

/**
 * Minimal ViewTree owners so ComposeView can live in a service window.
 * Without these, `setContent` throws.
 */
private class OverlayHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun attachTo(view: View) {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }

    /** Drops back to CREATED, not DESTROYED — DESTROYED is terminal and this host is
     *  reused across repeated show/hide cycles on the same session object. */
    fun pause() { registry.currentState = Lifecycle.State.CREATED }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
