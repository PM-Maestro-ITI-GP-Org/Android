package com.motorguard.ivi.data.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Where the media volume is, in whatever steps the platform uses. */
data class VolumeState(
    val level: Int = 0,
    val max: Int = 1,
    val muted: Boolean = false,
) {
    /** 0f..1f, for the slider. Guarded because a stream can report a max of zero. */
    val fraction: Float get() = if (max <= 0) 0f else (level.toFloat() / max).coerceIn(0f, 1f)
}

/**
 * The car's media volume.
 *
 * `STREAM_MUSIC` deliberately, not a per-source notion of loudness: everything the driver hears
 * as "music" — our player, a video, and the phone's A2DP stream — lands on that stream, so one
 * control moves all of them and the slider never disagrees with what is coming out of the
 * speakers.
 *
 * Changes are observed rather than assumed. The volume can move without anyone touching this UI
 * — the steering-wheel keys, the platform, or a phone that supports AVRCP absolute volume — and
 * a slider that only updates when dragged would sit there showing a stale position.
 */
class VolumeController(context: Context) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    fun current(): VolumeState {
        val manager = audio ?: return VolumeState()
        return runCatching {
            VolumeState(
                level = manager.getStreamVolume(STREAM),
                max = manager.getStreamMaxVolume(STREAM),
                muted = manager.isStreamMute(STREAM),
            )
        }.getOrDefault(VolumeState())
    }

    /**
     * Volume as it changes.
     *
     * `VOLUME_CHANGED_ACTION` is not in the SDK but has been broadcast under that name for the
     * life of the platform, and it is the only push signal there is — the alternative is polling
     * a slider position several times a second. The receiver re-reads rather than trusting the
     * intent's extras, so an action that is absent or renamed degrades to "no update" instead of
     * to a wrong number.
     */
    fun stream(): Flow<VolumeState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(current())
            }
        }
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED_ACTION)
            addAction(STREAM_MUTE_CHANGED_ACTION)
        }
        runCatching { appContext.registerReceiver(receiver, filter) }

        trySend(current())
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    /** [fraction] is 0f..1f from the slider; the platform's own step count does the rounding. */
    fun setFraction(fraction: Float) {
        val manager = audio ?: return
        runCatching {
            val max = manager.getStreamMaxVolume(STREAM)
            val target = (fraction.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
            manager.setStreamVolume(STREAM, target, 0)
        }
    }

    fun adjust(up: Boolean) {
        val manager = audio ?: return
        runCatching {
            manager.adjustStreamVolume(
                STREAM,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
        }
    }

    fun toggleMute() {
        val manager = audio ?: return
        runCatching {
            manager.adjustStreamVolume(STREAM, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC

        /** `AudioManager.VOLUME_CHANGED_ACTION` — `@hide`, hence the literal. */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
    }
}
