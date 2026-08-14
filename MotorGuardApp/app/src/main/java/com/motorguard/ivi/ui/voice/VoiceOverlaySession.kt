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
import java.util.Locale
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

        /** Enough to read as frosted glass without dissolving what is behind it. */
        const val BLUR_RADIUS_PX = 56

        /** Waveform range, in dBFS: a quiet cabin, and speech at a normal volume. */
        const val QUIET_DB = -45f
        const val LOUD_DB = -12f
    }

    private var model by mutableStateOf(VoiceUiModel())

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
                // No background: the window is transparent so the tab underneath shows
                // through, and the theme's own opaque fill would defeat that entirely.
                MotorGuardTheme(forceDark = true, paintBackground = false) {
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
        hideSystemBars()
        // Hand the mic from the always-on wake-word recorder to our recognizer;
        // running both at once starves STT and it reports "I didn't hear anything".
        VoiceOverlayService.pauseWakeWord()
        shownAtElapsedMs = SystemClock.elapsedRealtime()
        WakeTone.play(context)
        VoiceEngine.ensureReady()
        overlayHost?.resume()
        requestFocus()
        ensureTts()
        model = VoiceUiModel(state = VoiceState.LISTENING)
        startListening()
    }

    /**
     * Take the system bars down for the life of the session.
     *
     * MainActivity hides them for its own window, but this session is a *separate*
     * window owned by the platform, and the flags do not carry across — so summoning
     * Vega over the kiosk launcher made the AAOS status and nav bars slide back in
     * behind it, which is exactly the chrome the launcher exists to keep off screen.
     *
     * Applied on every show, not once at construction: the platform reuses one session
     * object across show/hide cycles, and the window is recreated underneath it.
     */
    private fun hideSystemBars() {
        val win = runCatching { window?.window }.getOrNull() ?: return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
        androidx.core.view.WindowInsetsControllerCompat(win, win.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // A swipe should not be able to pull the bars back over the assistant.
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        styleWindow(win)
    }

    /**
     * Let the app show through behind the assistant.
     *
     * The session window is opaque by default, so summoning Vega blacked the dash out
     * entirely and the panel floated on nothing. Clearing the background makes the screen
     * the driver was already looking at stay visible behind the scrim, which is both nicer
     * and less disorienting — the map or the album art is still there, just receded.
     *
     * Real blur is asked for only when the platform says it can do it.
     * WindowManager.isCrossWindowBlurEnabled is false on this image
     * (ro.surface_flinger.supports_background_blur is unset), and setting the flag anyway
     * costs a composition pass for nothing. Where it is supported the blur simply appears;
     * where it is not, the scrim in VoiceOverlayUi carries the separation on its own.
     */
    private fun styleWindow(win: android.view.Window) {
        runCatching {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val wm = context.getSystemService(android.view.WindowManager::class.java)
                if (wm?.isCrossWindowBlurEnabled == true) {
                    win.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    win.attributes = win.attributes.apply { blurBehindRadius = BLUR_RADIUS_PX }
                    Log.i(TAG, "cross-window blur enabled")
                }
            }
        }.onFailure { Log.w(TAG, "could not style the overlay window", it) }
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
        // Give the mic back to wake-word listening for the next "Hey Vega".
        VoiceOverlayService.resumeWakeWord()
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
            fail("Speech recognition isn't available on this build.")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    model = model.copy(state = VoiceState.LISTENING)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // dBFS from OfflineRecognitionService: 0 is full scale, about -45 a
                    // quiet cabin and -12 loud speech, so that span is what the waveform
                    // should spend its range on. The old -2..10 window was written for
                    // AOSP's recognizer and, against the raw amplitude this was actually
                    // sending, clamped to 1.0 on any sound whatsoever.
                    //
                    // Smoothed towards the new value rather than snapped: at one reading
                    // every 80 ms, raw levels make the bars flicker rather than move.
                    val target = ((rmsdB - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)
                    model = model.copy(level = model.level + (target - model.level) * 0.4f)
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
                        fail("Sorry, I didn't catch that.")
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
                                "I didn't hear anything."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "I need microphone permission."
                            else -> "Speech recognition failed."
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { fail("Could not start listening.") }
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
        // Phrases the owner taught, ahead of the compiled-in core: the usual reason to
        // teach one is that the built-in answer was not the wanted answer.
        com.motorguard.ivi.data.VoiceCommands.ensureLoaded(context)
        com.motorguard.ivi.data.VoiceCommands.answer(utterance)?.let { reply ->
            Log.i(TAG, "custom command matched")
            model = model.copy(state = VoiceState.SPEAKING, reply = reply)
            speak(reply)
            return
        }
        val reply = VoiceEngine.handle(utterance)
            ?: "Sorry, I didn't catch that. You can ask me to explain a warning " +
            "light, whether it's serious, or where the nearest garage is."
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
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
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
