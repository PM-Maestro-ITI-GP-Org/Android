package com.motorguard.ivi.ui.voice

import android.content.Context
import android.util.Log
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.MediaSourceManager
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.RepeatMode
import com.motorguard.ivi.data.media.VolumeController
import com.motorguard.ivi.media.MediaConnection
import kotlinx.coroutines.flow.first

/**
 * The transport controls, spoken.
 *
 * Handled here rather than by routing to the Media tab, because "skip this song" is not a request
 * to be shown a screen. Every one of these already worked as a tap; what they did not have was a
 * way to happen while the driver's hands were on the wheel, which is the entire argument for a
 * voice assistant in a car.
 *
 * [MediaConnection] is a process-wide singleton over the same MediaSession the tab drives, so
 * what is said here and what the Media tab shows cannot disagree — there is only one player.
 *
 * Returns null when the utterance is not about playback, in which case the caller falls through
 * to the matcher and the core untouched.
 */
object MediaVoice {

    private const val TAG = "MotorGuardVoice"

    /** What the driver asked for. Separated from doing it so the matching is unit-testable. */
    internal enum class Ask {
        PLAY, PAUSE, NEXT, PREVIOUS, SHUFFLE, REPEAT, NOW_PLAYING,
        VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE,
    }

    fun handle(context: Context, utterance: String): String? {
        val ask = intentOf(utterance) ?: return null
        Log.i(TAG, "media command: $ask")
        return runCatching { act(context.applicationContext, ask) }
            .getOrElse {
                Log.e(TAG, "media command failed", it)
                "Sorry, I couldn't reach the player."
            }
    }

    /**
     * The now-playing answer regardless of wording — for the embedding matcher, which has already
     * decided this is the question. Safe to reach that way precisely because it only reads.
     */
    fun answerNowPlaying(context: Context): String =
        runCatching { nowPlaying(MediaConnection.get(context.applicationContext).state.value) }
            .getOrElse { "Sorry, I couldn't reach the player." }

    // --- switching source ----------------------------------------------------

    /**
     * Which source "play from USB" meant, or null.
     *
     * Kept apart from [intentOf] because it carries a value rather than a verb, and because
     * acting on it suspends: availability and the track list are both reads off the source.
     *
     * "Take me home" is not the only phrase that has to be excluded deliberately — "put the radio
     * on" must not be caught by [intentOf]'s bare PLAY, which is why that test is last and exact.
     */
    internal fun sourceOf(utterance: String): MediaSourceId? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null
        if (YOUTUBE_MUSIC.any { text.contains(it) }) return MediaSourceId.YOUTUBE_MUSIC
        if (USB.any { text.contains(it) }) return MediaSourceId.USB
        if (BLUETOOTH.any { text.contains(it) }) return MediaSourceId.BLUETOOTH
        if (RADIO.any { text.contains(it) }) return MediaSourceId.RADIO
        if (LIBRARY.any { text.contains(it) }) return MediaSourceId.LOCAL
        return null
    }

    private val YOUTUBE_MUSIC = listOf("youtube music", "yt music")
    private val USB = listOf("usb", "the stick", "memory stick", "flash drive", "thumb drive")
    private val BLUETOOTH = listOf("bluetooth", "blue tooth", "from my phone", "off my phone")
    private val RADIO = listOf("radio", "fm", "a station")
    private val LIBRARY = listOf("my library", "the library", "local music", "my own music")

    /**
     * Switch, and start something playing if this source is ours to play.
     *
     * The availability message comes from the source itself — "Insert USB drive" and "Connect a
     * phone" are not interchangeable, and only the source knows which applies. Saying its own
     * sentence is what keeps the spoken failure and the one on the tab identical.
     *
     * [PlaybackKind.EXTERNAL_SESSION] switches and stops there: Bluetooth audio is the phone's
     * session mirrored, so there is no queue here to load and pretending otherwise would report
     * playback that never started. [PlaybackKind.STREAM] does load, because radio is our own
     * player holding the socket.
     */
    suspend fun playFrom(context: Context, id: MediaSourceId): String {
        val app = context.applicationContext
        val manager = MediaSourceManager.get(app)
        val source = manager.source(id)

        val availability = runCatching { source.availability().first() }.getOrNull()
        if (availability != null && !availability.available) return availability.emptyMessage

        val connection = MediaConnection.get(app)
        connection.setSource(id)

        if (source.playbackKind != PlaybackKind.LOCAL_PLAYER && source.playbackKind != PlaybackKind.STREAM) {
            // Bluetooth still needs telling: switching the *source* only changes which mirror
            // MediaConnection reads, it sends the phone nothing. Without an explicit AVRCP play
            // command a phone sitting paused stays paused, so "play from my phone" switched the
            // tab and then did nothing audible.
            if (id == MediaSourceId.BLUETOOTH) {
                com.motorguard.ivi.media.BluetoothSessionMirror.get(app).play()
            }
            return "Switched to ${source.label}."
        }
        val tracks = runCatching { manager.tracks(id) }.getOrElse { emptyList() }
        if (tracks.isEmpty()) {
            return availability?.emptyMessage ?: "There's nothing to play on ${source.label}."
        }
        connection.play(tracks, 0)
        return "Playing from ${source.label}."
    }

    // --- what was asked ------------------------------------------------------

    /**
     * Keyword matching only, deliberately — no embedding anchors for anything that *acts*.
     *
     * [com.motorguard.ivi.ui.voice.nlu.IntentMatcher]'s threshold was measured against phrasings
     * that should route and questions that should not, and every anchor added sits closer to its
     * neighbours. A misheard question gives an odd answer; a misheard command changes what the
     * car is doing. The one exception below is [Ask.NOW_PLAYING], which only reads.
     *
     * Ordered longest-intent-first: "turn the music up" is a volume command and must not be
     * caught by the bare "play"/"music" tests further down.
     */
    internal fun intentOf(utterance: String): Ask? {
        val text = normalise(utterance)
        if (text.isEmpty()) return null

        NOW_PLAYING.firstOrNull { text.contains(it) }?.let { return Ask.NOW_PLAYING }
        if (UNMUTE.any { text.contains(it) }) return Ask.UNMUTE
        if (MUTE.any { text.contains(it) }) return Ask.MUTE
        if (VOLUME_UP.any { text.contains(it) }) return Ask.VOLUME_UP
        if (VOLUME_DOWN.any { text.contains(it) }) return Ask.VOLUME_DOWN
        if (PREVIOUS.any { text.contains(it) }) return Ask.PREVIOUS
        if (NEXT.any { text.contains(it) }) return Ask.NEXT
        if (SHUFFLE.any { text.contains(it) }) return Ask.SHUFFLE
        if (REPEAT.any { text.contains(it) }) return Ask.REPEAT
        if (PAUSE.any { text.contains(it) }) return Ask.PAUSE

        // Bare "play" last and only on its own: "play some music" is a request to browse, which
        // is the Media tab's job and stays with the route anchor that already handles it.
        return if (text in PLAY_EXACT) Ask.PLAY else null
    }

    private val NOW_PLAYING = listOf(
        "what is playing", "whats playing", "what s playing", "what is this song",
        "whats this song", "what song is this", "who is this", "who sings this",
        "what am i listening to", "name of this song",
    )
    private val UNMUTE = listOf("unmute", "un mute", "sound back on", "turn the sound back on")
    private val MUTE = listOf("mute", "silence the music", "no sound")
    private val VOLUME_UP = listOf(
        "volume up", "turn it up", "turn the volume up", "turn the music up", "louder",
        "raise the volume", "crank it",
    )
    private val VOLUME_DOWN = listOf(
        "volume down", "turn it down", "turn the volume down", "turn the music down",
        "quieter", "lower the volume", "too loud",
    )
    private val PREVIOUS = listOf(
        "previous track", "previous song", "go back a track", "last song", "play that again",
        "go back a song", "previous",
    )
    private val NEXT = listOf(
        "next track", "next song", "skip this", "skip it", "skip the song", "skip this song",
        "next one", "next",
    )
    private val SHUFFLE = listOf("shuffle")
    private val REPEAT = listOf("repeat")
    private val PAUSE = listOf("pause", "stop the music", "stop the song", "shut it off")
    private val PLAY_EXACT = setOf("play", "resume", "unpause", "keep playing", "carry on", "resume playback")

    // --- doing it ------------------------------------------------------------

    private fun act(context: Context, ask: Ask): String {
        val connection = MediaConnection.get(context)
        val now = connection.state.value

        return when (ask) {
            Ask.NOW_PLAYING -> nowPlaying(now)

            // playPause() toggles, so a bare toggle would start the music for a driver who said
            // "pause" to something already paused. Both commands check first and become no-ops
            // rather than doing the opposite of what was asked.
            Ask.PLAY -> when {
                now.isPlaying -> "That's already playing."
                !now.hasTrack -> "There's nothing queued up to play."
                else -> { connection.playPause(); "Playing." }
            }
            Ask.PAUSE -> when {
                !now.isPlaying -> "Nothing's playing."
                else -> { connection.playPause(); "Paused." }
            }

            Ask.NEXT -> when {
                !now.hasTrack -> "Nothing's playing."
                !now.canSkip -> "This source can't skip tracks."
                else -> { connection.next(); "Next track." }
            }
            Ask.PREVIOUS -> when {
                !now.hasTrack -> "Nothing's playing."
                !now.canSkip -> "This source can't skip tracks."
                else -> { connection.previous(); "Going back." }
            }

            // The reply names the state being moved *to*. Reading it back off the snapshot would
            // race the player's own round trip and report the state that is on its way out.
            Ask.SHUFFLE -> {
                connection.toggleShuffle()
                if (now.shuffle) "Shuffle off." else "Shuffle on."
            }
            Ask.REPEAT -> {
                connection.cycleRepeat()
                repeatReply(nextRepeat(now.repeat))
            }

            Ask.VOLUME_UP, Ask.VOLUME_DOWN -> {
                val volume = VolumeController(context)
                volume.adjust(up = ask == Ask.VOLUME_UP)
                val state = volume.current()
                if (state.max <= 0) "Done." else "Volume ${state.level} of ${state.max}."
            }
            Ask.MUTE -> {
                val volume = VolumeController(context)
                if (volume.current().muted) "It's already muted."
                else { volume.toggleMute(); "Muted." }
            }
            Ask.UNMUTE -> {
                val volume = VolumeController(context)
                if (!volume.current().muted) "It isn't muted."
                else { volume.toggleMute(); "Sound back on." }
            }
        }
    }

    /** Pure, so the sentence can be pinned without a player. */
    internal fun nowPlaying(snapshot: PlaybackSnapshot): String {
        val track = snapshot.track ?: return "Nothing's playing."
        val name = track.title.trim().ifEmpty { return "Something's playing, but it hasn't given me a title." }
        val artist = track.artist.trim()
        val lead = if (snapshot.isPlaying) "This is" else "Paused on"
        return if (artist.isEmpty()) "$lead $name." else "$lead $name, by $artist."
    }

    /** Mirrors [MediaConnection.cycleRepeat]'s off → all → one. */
    internal fun nextRepeat(current: RepeatMode): RepeatMode = when (current) {
        RepeatMode.OFF -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.ONE
        RepeatMode.ONE -> RepeatMode.OFF
    }

    internal fun repeatReply(mode: RepeatMode): String = when (mode) {
        RepeatMode.OFF -> "Repeat off."
        RepeatMode.ALL -> "Repeating everything."
        RepeatMode.ONE -> "Repeating this track."
    }

    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
