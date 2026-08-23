// WakeWordService — owner D
// A microphone-type foreground service whose only job is to own the wake-word
// detector, so that capture is never silenced.
package com.motorguard.ivi.ui.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Why this exists at all.
 *
 * The detector needs a continuous microphone stream. Two capture sources were
 * available and neither works from a plain background service on this build:
 *
 *  - VOICE_RECOGNITION is subject to the RECORD_AUDIO appop's MODE_FOREGROUND
 *    default. AudioService logs "rec start ... not silenced", flips it to
 *    "silenced" once the app settles into the background, and read() then
 *    returns 0 forever with no error at any layer. The UID-level appop cannot
 *    be forced past this by hand -- its mode is derived from procstate.
 *
 *  - HOTWORD (with CAPTURE_AUDIO_HOTWORD, priv-app, privapp-permissions) is
 *    exempt from that restriction and does deliver frames, but on this board it
 *    delivers *synthetic noise* rather than microphone audio: a C-Media dongle
 *    and a Logitech headset produced statistically identical streams (sigma
 *    ~7700, peak ~3.3 sigma, flat mel spectrum), and the level scaled by exactly
 *    sqrt(2) between mono and stereo -- independent noise per channel, which a
 *    real duplicated mono capsule cannot produce. The Pi 5 has no DSP hotword
 *    path behind the API, so the platform hands back filler.
 *
 * A foreground service is the third route: it keeps the app out of the
 * background procstate entirely, so the foreground restriction never applies and
 * VOICE_RECOGNITION -- the source that reaches the real capsule -- stays live.
 *
 * VoiceInteractionService cannot do this itself: it is bound by the system
 * rather than started, so it cannot call startForeground() on its own. Hence a
 * separate service that it starts.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "MotorGuardVoice"
        private const val CHANNEL_ID = "motorguard_wakeword"
        private const val NOTIFICATION_ID = 0x4D47   // "MG"

        /** Set by the service while it is alive, so the session can pause it. */
        @Volatile
        private var detector: WakeWordDetector? = null

        /** Called by VoiceOverlayService.onReady(). */
        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "could not start wake-word service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeWordService::class.java)) }
        }

        /**
         * Released while a session holds the mic, then resumed. Two clients on
         * one USB capture device is not reliable, and the session's recognizer
         * needs it more than the detector does.
         *
         * Calls the detector's own pause()/resume() -- NOT stop()/start(). Those tear
         * down and reload the three ONNX sessions on every hand-off, which cost enough
         * that the USB device was often still mid-teardown from the just-closed
         * recognizer mic when this tried to reopen it, so every real pause/resume did:
         * paused -> mic contention -> "no working mic configuration" -> "did not
         * resume", permanently killing the detector until the next lucky retry (or a
         * process restart) actually landed outside the race window. pause()/resume()
         * keep the models warm, so this is both correct and far faster.
         */
        fun pause() {
            detector?.pause()
        }

        fun resume() {
            detector?.resume()
        }

        /** Last score seen, for the tuning readout. */
        fun lastScore(): Float = detector?.lastScore ?: 0f
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() must happen within a few seconds of
        // startForegroundService() or the system throws
        // ForegroundServiceDidNotStartInTimeException, so do it before any of
        // the slow model-loading work.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (detector == null) {
            // Opening the mic probes several candidate configs, each waiting up
            // to PROBE_MS on a blocking read. Done on the start-binder thread that
            // would otherwise be a 4-12 s main-thread stall -> ANR.
            Thread({
                val d = createWakeWordDetector(this)
                val started = d.start {
                    // Fired off the audio thread; keep this tiny.
                    if (!VoiceOverlayService.requestSession()) {
                        Log.w(TAG, "wake word fired but no session could be shown")
                    }
                }
                if (started) {
                    detector = d
                } else {
                    Log.w(TAG, "no wake word running — use the rail mic button instead")
                    // Stay foreground anyway: the service is cheap, and a later
                    // resume() can still succeed once the mic frees up.
                }
            }, "wake-word-start").start()
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = runCatching {
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Motor Guard")
                .setContentText("Listening for the wake word")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()

        // The microphone type is the point of the whole exercise: it is what
        // exempts this process from the background capture restriction.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    }.getOrElse {
        Log.e(TAG, "startForeground failed — mic will be silenced in background", it)
        false
    }

    private fun createChannel() = runCatching {
        val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return@runCatching
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wake word",
            // MIN so a headless car build does not show a heads-up card. The
            // notification still exists, which is what the platform requires.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps the microphone open for the wake word"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }.onFailure { Log.e(TAG, "could not create notification channel", it) }

    override fun onDestroy() {
        detector?.stop()
        detector = null
        super.onDestroy()
    }
}