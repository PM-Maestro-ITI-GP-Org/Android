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
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
