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
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.data.media.sources.RadioMediaSource
import com.motorguard.ivi.media.MediaConnection
import com.motorguard.ivi.ui.theme.MotorGuardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    companion object {
        /**
         * Whether an overlay session is on screen.
         *
         * Read by [MotorFaultAnnouncer], which must not volunteer a fault over a question the
         * driver is in the middle of asking. A plain flag rather than a flow: the only consumer
         * asks once, at the moment it is about to speak, and a session is always shown and hidden
         * on the main thread.
         */
        @Volatile
        var isVisible: Boolean = false
            private set

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

    /**
     * For commands that suspend. Main-immediate because everything it touches afterwards — the
     * Compose model and the TTS engine — is main-thread confined, and the work itself suspends on
     * IO rather than blocking. Cancelled in [onDestroy]; individual commands in [onHide].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commandJob: Job? = null
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
        isVisible = true
        hideSystemBars()
        // Hand the mic from the always-on wake-word recorder to our recognizer;
        // running both at once starves STT and it reports "I didn't catch that."
        VoiceOverlayService.pauseWakeWord()
        shownAtElapsedMs = SystemClock.elapsedRealtime()
        WakeTone.play(context)
        VoiceEngine.ensureReady()
        // First call loads an 87 MB model and embeds the anchors, so do it off the main
        // thread while the driver is still speaking rather than in the pause afterwards.
        Thread({
            com.motorguard.ivi.ui.voice.nlu.IntentMatcher.ensureReady(context)
        }, "intent-warmup").start()
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
        isVisible = false
        commandJob?.cancel()
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
        scope.cancel()
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
                        fail("I didn't catch that.")
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
                                "I didn't catch that."
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
        // The motor's own signal, answered rather than routed.
        //
        // The diagnostics unit on the other board has already done the classifying, and its
        // verdict is a sentence — "an electrical fault, flagged as critical". Opening the
        // diagnostics tab so the driver can read that off a card is not an answer to a question
        // asked out loud, and the core cannot answer it either: its catalogue is compiled-in DTCs
        // and this fault is not one of them.
        //
        // Ahead of the matcher because the matcher would route "is anything wrong with the motor"
        // to the DIAGNOSTICS tab on an anchor written before this existed. Behind the taught
        // phrases, for the reason they are ahead of everything: teaching one is an instruction.
        MotorVoice.handle(utterance)?.let { reply ->
            Log.i(TAG, "motor question answered from live telemetry")
            model = model.copy(state = VoiceState.SPEAKING, reply = reply)
            speak(reply)
            return
        }
        // The rest of the head unit, acted on rather than opened.
        //
        // Same argument as the motor: "skip this song" and "how long until we arrive" are not
        // requests to be shown a screen, and answering them with one is how an assistant makes
        // a driver look away from the road to read something it already knew. Each handler
        // returns null for anything outside its domain, so the order among them is only about
        // which claims an overlapping phrase first — and none of them overlap today.
        //
        // All ahead of the matcher, which would otherwise route these to a tab on anchors
        // written before any of it could act.
        MediaVoice.handle(context, utterance)?.let { reply ->
            model = model.copy(state = VoiceState.SPEAKING, reply = reply)
            speak(reply)
            return
        }
        // The two that have to go and ask something before they can answer: a source has an
        // availability and a track list to read, and a destination has a search and a route
        // behind it. Both show THINKING meanwhile — the overlay already has the state, and a
        // silent overlay for the second a route takes reads as one that did not hear.
        MediaVoice.sourceOf(utterance)?.let { source ->
            answerAsync { MediaVoice.playFrom(context, source) }
            return
        }
        NavVoice.destinationOf(utterance)?.let { place ->
            answerAsync { NavVoice.navigateTo(context, place) }
            return
        }
        NavVoice.handle(utterance)?.let { reply ->
            model = model.copy(state = VoiceState.SPEAKING, reply = reply)
            speak(reply)
            return
        }
        ThemeVoice.handle(utterance)?.let { reply ->
            model = model.copy(state = VoiceState.SPEAKING, reply = reply)
            speak(reply)
            return
        }
        // Meaning before keywords, and before the core.
        //
        // The obvious order — core first, matcher as a fallback — cannot work: the C++ core
        // never returns null. It answers everything, apologising in its own words when it does
        // not know ("...or find the nearest garage"), so nothing downstream of it ever ran.
        //
        // The threshold is what makes this safe to put first: the matcher returns null unless
        // it is genuinely confident, so anything it declines still reaches the core with its
        // fault catalogue intact.
        com.motorguard.ivi.ui.voice.nlu.IntentMatcher.match(utterance)?.let { intent ->
            intent.reply?.let { taught ->
                model = model.copy(state = VoiceState.SPEAKING, reply = taught)
                speak(taught)
                return
            }
            intent.ask?.let { ask ->
                // Matched by meaning rather than by wording, so the answer is composed here
                // instead of by MotorVoice.claims() above — which has already declined it.
                val reply = when (ask) {
                    com.motorguard.ivi.ui.voice.nlu.VoiceAsk.MOTOR_STATUS -> MotorVoice.answerNow(utterance)
                    com.motorguard.ivi.ui.voice.nlu.VoiceAsk.NOW_PLAYING -> MediaVoice.answerNowPlaying(context)
                    com.motorguard.ivi.ui.voice.nlu.VoiceAsk.TRIP_PROGRESS -> NavVoice.answerProgress()
                }
                model = model.copy(state = VoiceState.SPEAKING, reply = reply)
                speak(reply)
                return
            }
            intent.route?.let { target ->
                // "Watch youtube" and "open youtube" both land on VIDEO — the anchors don't
                // distinguish a film on this device from one on the web, only route() and
                // VideoScreen's own toggle do that. Presetting it here is what makes the tab
                // open already on YouTube instead of the library, which is what was asked for.
                if (target == VoiceRoute.VIDEO && utterance.lowercase(Locale.US).contains("youtube")) {
                    com.motorguard.ivi.ui.web.WebSession.YouTube.selected = true
                }
                val reply = "Opening ${target.label.lowercase()}."
                model = model.copy(state = VoiceState.SPEAKING, reply = reply)
                // Route once the confirmation has actually finished being heard, not after a
                // guessed delay -- see [speak]'s `onSpoken` for why a fixed timer cut long
                // replies off mid-sentence.
                speak(reply) { route(target) }
                return
            }
        }

        // The core last, and it always answers — including its own apology when it cannot.
        //
        // The apology names nothing it can do. It used to list three, which was already only a
        // fraction of them and is now a much smaller fraction — and a driver who has just not
        // been understood is being made to sit through a menu before they can try again. "What
        // can you do" is a question they can ask when they actually want the list.
        val reply = VoiceEngine.handle(utterance) ?: "I didn't catch that."
        Log.i(TAG, "reply: $reply")
        model = model.copy(state = VoiceState.SPEAKING, reply = reply)
        speak(reply)
    }

    /**
     * Run a command that has to wait, and speak whatever it concludes.
     *
     * Cancelled in [onHide]: the driver closing the overlay mid-route-search should not be
     * spoken to a moment later by a session that is no longer on screen. The failure branch
     * speaks rather than staying silent, because an assistant that goes quiet is
     * indistinguishable from one that crashed.
     */
    private fun answerAsync(block: suspend () -> String) {
        model = model.copy(state = VoiceState.THINKING)
        commandJob?.cancel()
        commandJob = scope.launch {
            val reply = runCatching { block() }.getOrElse {
                Log.e(TAG, "voice command failed", it)
                "Sorry, that didn't work."
            }
            model = model.copy(state = VoiceState.SPEAKING, reply = reply)
            speak(reply)
        }
    }

    private fun fail(message: String) {
        stopListening()
        model = model.copy(state = VoiceState.SPEAKING, reply = message, level = 0f)
        speak(message)
    }

    /**
     * What to do once the current reply has actually finished being spoken — a tab switch, for
     * instance, that must not cut the sentence announcing it off mid-word. Set by [speak]'s
     * `onSpoken`, run once from [ensureTts]'s `onDone`, then cleared so a later reply with
     * nothing to do afterwards does not re-run a stale action.
     */
    private var onSpoken: (() -> Unit)? = null

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    // TextToSpeech delivers these off the main thread; every one of `onSpoken`,
                    // `model` and `scheduleDismiss` is main-thread-confined, so both branches
                    // post back rather than running here.
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            onSpoken?.invoke()
                            onSpoken = null
                            scheduleDismiss()
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        handler.post {
                            onSpoken = null
                            scheduleDismiss()
                        }
                    }
                    override fun onStart(utteranceId: String?) {}
                })
            } else {
                Log.w(TAG, "TTS unavailable; overlay will show text only")
            }
        }
    }

    /**
     * @param onSpoken run once [text] has finished being heard — after a route or another
     *   action that should not talk over its own confirmation. Runs immediately, inline, when
     *   there is no TTS engine to wait on: no speech means nothing to finish waiting for.
     */
    private fun speak(text: String, onSpoken: (() -> Unit)? = null) {
        this.onSpoken = onSpoken
        val engine = tts
        if (engine == null) {
            this.onSpoken = null
            onSpoken?.invoke()
            scheduleDismiss()
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        // Belt and braces: if the callback never lands, don't leave the bar (or a pending
        // action) stuck forever.
        handler.postDelayed({
            if (model.state == VoiceState.SPEAKING) {
                this.onSpoken?.invoke()
                this.onSpoken = null
                scheduleDismiss()
            }
        }, 20_000L)
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
        // MEDIA used to be tab-navigation only: it opened the Media tab but never played
        // anything, so "play music" got a TTS reply claiming to be playing music while the
        // driver stared at a silent, empty tab.
        if (target == VoiceRoute.MEDIA) playDefaultMedia()
        requestHide()
    }

    /**
     * "Play music" with nothing else to go on: resume whatever is already loaded, or fall back
     * Local library -> USB -> Bluetooth -> Radio (radio only if there is a network to stream
     * from) -- the order a driver would try them in by hand, cheapest and most-likely-to-work
     * first.
     *
     * Fetches off the main thread (a MediaStore query or a network call has no business blocking
     * it), but MediaConnection's transport calls go through a Media3 MediaController, which
     * throws IllegalStateException off the main thread -- confirmed live, see the history of this
     * function. Every call into it is handed back to [handler] (main-looper).
     */
    private fun playDefaultMedia() {
        Thread({
            runCatching {
                val connection = MediaConnection.get(context)
                if (connection.state.value.hasTrack) {
                    if (!connection.state.value.isPlaying) handler.post { connection.playPause() }
                    return@runCatching
                }

                val manager = MediaSourceManager.get(context)
                val local = kotlinx.coroutines.runBlocking { manager.tracks(MediaSourceId.LOCAL) }
                if (local.isNotEmpty()) {
                    handler.post {
                        connection.setSource(MediaSourceId.LOCAL)
                        connection.play(local, 0)
                    }
                    return@runCatching
                }

                val usb = kotlinx.coroutines.runBlocking { manager.tracks(MediaSourceId.USB) }
                if (usb.isNotEmpty()) {
                    handler.post {
                        connection.setSource(MediaSourceId.USB)
                        connection.play(usb, 0)
                    }
                    return@runCatching
                }

                if (com.motorguard.ivi.data.Conn.bt.connectedName != null) {
                    handler.post {
                        connection.setSource(MediaSourceId.BLUETOOTH)
                        connection.playPause()
                    }
                    return@runCatching
                }

                if (!hasInternet(context)) {
                    Log.i(TAG, "playDefaultMedia: nothing available (no local/usb/phone/network)")
                    return@runCatching
                }
                val source = manager.source(MediaSourceId.RADIO) as? RadioMediaSource
                    ?: return@runCatching
                val stations = kotlinx.coroutines.runBlocking { source.tracks() }
                if (stations.isNotEmpty()) {
                    handler.post {
                        connection.setSource(MediaSourceId.RADIO)
                        connection.play(stations, 0)
                    }
                } else {
                    Log.w(TAG, "playDefaultMedia: no stations returned")
                }
            }.onFailure { Log.e(TAG, "playDefaultMedia failed", it) }
        }, "voice-play-music").start()
    }

    /** NET_CAPABILITY_VALIDATED, not merely CONNECTED — see RadioMediaSource.hasInternet(),
     *  which this mirrors: a joined network with no route out must not read as available. */
    private fun hasInternet(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

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
