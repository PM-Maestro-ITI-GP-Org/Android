package com.motorguard.ivi.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log

/**
 * The ringtone for an incoming hands-free call.
 *
 * HFP gives a car two ways to ring, and which one applies is the phone's choice. A handset
 * with in-band ringing sends the tone itself over SCO, and the right thing for the head unit
 * to do is stay quiet and let it through. A handset without it sends only the call state, and
 * the hands-free unit is expected to ring on its own — which this one never did, so an
 * incoming call arrived in complete silence.
 *
 * The two are not told apart up front by asking the AG about in-band support. The local tone
 * starts when the call starts ringing and stops the moment SCO carries audio instead, so the
 * answer arrives by observation, works on both kinds of phone, and the two can never overlap.
 *
 * Outgoing calls are deliberately not covered: ringback belongs to the network, and a locally
 * generated one would keep ringing after the far end had already picked up.
 */
class CallRingtone(private val app: Context) {

    private var player: MediaPlayer? = null

    /** Driven from the live call; safe to call with the same state repeatedly. */
    fun update(call: ActiveCall?, inBandAudio: Boolean) {
        val shouldRing = call != null &&
            call.state == CallState.RINGING &&
            call.direction == CallDirection.INCOMING &&
            !inBandAudio
        if (shouldRing) start() else stop()
    }

    private fun start() {
        if (player != null) return
        val uri = runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }.getOrNull() ?: run {
            Log.w(TAG, "no default ringtone on this image")
            return
        }

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(RINGTONE_USAGE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(app, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "ringtone error $what/$extra")
                    true
                }
                // Async: this runs on the main thread, and a ringtone that stutters the
                // launcher while it loads is worse than one that starts 20 ms late.
                prepareAsync()
            }
        }.onFailure {
            Log.w(TAG, "ringtone could not be started", it)
            stop()
        }
    }

    private fun stop() {
        val live = player ?: return
        player = null
        runCatching { live.reset() }
        runCatching { live.release() }
    }

    private companion object {
        const val TAG = "MotorGuardPhone"

        /**
         * USAGE_NOTIFICATION_RINGTONE is the semantically correct usage, and the wrong one
         * here. `car_audio_configuration.xml` routes purely by usage → context → volume
         * group, and on this board the group that usage lands in has no real speaker behind
         * it, only HDMI — the same trap that sent the wake tone out of the screen instead of
         * the USB speaker (see WakeTone). Match the usage the rest of the audible output
         * already uses so the driver actually hears the phone ring.
         */
        const val RINGTONE_USAGE = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }
}
