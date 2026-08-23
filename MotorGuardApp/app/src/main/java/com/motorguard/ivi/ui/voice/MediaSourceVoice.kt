package com.motorguard.ivi.ui.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.media.MediaConnection
import kotlinx.coroutines.runBlocking

/**
 * "Open the USB drive", "switch to Spotify", "play the radio" — naming a Media source by name,
 * one command per tab on the Media screen.
 *
 * Kept apart from [MediaVoice] for the reason that file gives for keeping itself apart from the
 * embedding matcher: naming a specific source is an action, and every acting handler in this
 * package matches by keyword rather than by meaning, so a misheard word changes what the car
 * does rather than giving an odd answer.
 */
object MediaSourceVoice {

    private const val TAG = "MotorGuardVoice"

    fun handle(context: Context, utterance: String): String? {
        val id = sourceOf(utterance) ?: return null
        Log.i(TAG, "media source command: $id")
        return runCatching { act(context.applicationContext, id) }
            .getOrElse {
                Log.e(TAG, "media source command failed", it)
                "Sorry, I couldn't reach the player."
            }
    }

    private fun sourceOf(utterance: String): MediaSourceId? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        return when {
            SPOTIFY.any { text.contains(it) } -> MediaSourceId.SPOTIFY
            USB.any { text.contains(it) } -> MediaSourceId.USB
            BLUETOOTH.any { text.contains(it) } -> MediaSourceId.BLUETOOTH
            RADIO.any { text.contains(it) } -> MediaSourceId.RADIO
            LIBRARY.any { text.contains(it) } -> MediaSourceId.LOCAL
            else -> null
        }
    }

    private val SPOTIFY = listOf("open spotify", "play spotify", "switch to spotify", "go to spotify")
    private val USB = listOf(
        "open usb", "play usb", "switch to usb", "usb music", "the usb drive", "the usb stick",
    )
    private val BLUETOOTH = listOf(
        "open bluetooth music", "play bluetooth music", "switch to bluetooth music",
        "play music from my phone", "play from my phone",
    )
    private val RADIO = listOf(
        "open radio", "play the radio", "play radio", "switch to radio", "fm radio",
        "turn on the radio",
    )
    private val LIBRARY = listOf(
        "open my library", "open the library", "play my library", "play local music",
        "switch to my library", "switch to local", "play music from this device",
    )

    private fun act(context: Context, id: MediaSourceId): String {
        val connection = MediaConnection.get(context)
        val manager = MediaSourceManager.get(context)

        return when (id) {
            MediaSourceId.BLUETOOTH -> {
                if (Conn.bt.connectedName == null) return "No phone connected over Bluetooth."
                connection.setSource(id)
                "Switched to Bluetooth."
            }
            MediaSourceId.SPOTIFY -> {
                connection.setSource(id)
                "Opening Spotify."
            }
            else -> {
                connection.setSource(id)
                loadAndPlay(manager, connection, id)
                "Opening ${manager.source(id).label.lowercase()}."
            }
        }
    }

    /**
     * The library/USB/radio scan is I/O — a MediaStore query or a network call — that has no
     * business running on the thread that just replied. MediaConnection.play() must run on the
     * main thread (Media3 throws otherwise), so the result is handed back to a main-looper
     * handler for that one call, the same pattern [VoiceOverlaySession.playDefaultMedia] uses.
     */
    private fun loadAndPlay(manager: MediaSourceManager, connection: MediaConnection, id: MediaSourceId) {
        val handler = Handler(Looper.getMainLooper())
        Thread({
            val tracks = runCatching { runBlocking { manager.tracks(id) } }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) handler.post { connection.play(tracks, 0) }
        }, "voice-open-source").start()
    }

    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
