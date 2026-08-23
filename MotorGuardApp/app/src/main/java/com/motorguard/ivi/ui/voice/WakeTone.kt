package com.motorguard.ivi.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.motorguard.ivi.R

/**
 * The short two-note chime played the instant a voice session opens — wake word or
 * mic-button trigger alike, so there's always an audible cue that listening started.
 *
 * A plain [MediaPlayer] is enough for a ~300 ms one-shot: the raw resource is a few KB,
 * so the synchronous `create()` prepare is imperceptible, and there's no case where two
 * overlap (one session at a time) that would call for SoundPool's preload path.
 */
object WakeTone {
    private const val TAG = "MotorGuardVoice"

    fun play(context: Context) {
        runCatching {
            // MediaPlayer.create(context, resid) leaves the player already PREPARED, and
            // setAudioAttributes() is rejected in that state at the native layer ("trying
            // to set audio attributes called in state 8") — attributes have to go in at
            // construction time via this overload instead.
            //
            // USAGE_ASSISTANCE_SONIFICATION, not USAGE_ASSISTANCE_NAVIGATION_GUIDANCE: it
            // is the semantically correct usage for a UI chime. But car_audio_configuration.xml
            // routes car audio purely by usage -> context, and "system_sound" (what
            // ASSISTANCE_SONIFICATION maps to) sits in the Speaker group -- which on this
            // board has no real speaker behind it, so the chime came out of the HDMI/screen
            // audio while the spoken response right after it (USAGE_ASSISTANCE_NAVIGATION_
            // GUIDANCE, same as VoiceOverlaySession's TTS and MotorFaultAnnouncer) correctly
            // used the USB speaker. Match their usage so the whole wake interaction — tone
            // and speech alike — comes out of the same device.
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val player = MediaPlayer.create(
                context,
                R.raw.wake_tone,
                attrs,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ) ?: run {
                Log.w(TAG, "wake tone unavailable")
                return
            }
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); true }
            player.start()
        }.onFailure { Log.w(TAG, "wake tone playback failed", it) }
    }
}
