package com.motorguard.ivi.data.media

import android.net.Uri

/**
 * Provider-agnostic media domain, mirroring the role `data/nav` plays for navigation.
 *
 * The one idea worth understanding here: the three sources in docs/04-media.md are **not the
 * same kind of thing**, and pretending otherwise is what makes automotive media code rot.
 *  - USB and the on-device library are *files*. Our ExoPlayer opens them.
 *  - Bluetooth is *someone else's playback*. The phone owns the audio; we mirror its metadata
 *    over AVRCP and send it transport commands. We never hold that stream.
 *  - Radio is *hardware*. There is no file and no session, just a tuner and a frequency.
 *
 * [PlaybackKind] names that difference so the UI can stay uniform while the plumbing does not
 * have to lie.
 */

/** Which source a track came from, and which tab owns it. */
enum class MediaSourceId { LOCAL, USB, BLUETOOTH, RADIO, VIDEO }

/** How audio is actually produced for a source. */
enum class PlaybackKind {
    /** Our ExoPlayer opens the URIs directly. Seeking, queueing and shuffle are all real. */
    LOCAL_PLAYER,

    /** Another app's `MediaSession` owns the audio; we mirror it and send transport commands. */
    EXTERNAL_SESSION,

    /** A hardware tuner: no queue, no seeking, no duration. */
    TUNER,

    /**
     * A live stream we do open ourselves — internet radio.
     *
     * Neither of the others fits. It is our ExoPlayer holding the socket, so it is not
     * [EXTERNAL_SESSION]; but a live stream has no duration and no meaningful position, so
     * treating it as [LOCAL_PLAYER] hands the driver a scrubber that cannot do anything.
     * Skipping *is* meaningful — it moves to the next station — which is what separates this
     * from [TUNER], where nothing can be skipped at all.
     */
    STREAM,

    /**
     * A video file, played by a **local** player rather than the background service.
     *
     * Video is the one source that cannot go through the shared session. Rendering needs a
     * surface, and `MediaController` refuses `COMMAND_SET_VIDEO_SURFACE` — a controller talks to
     * a session in another process and there is no surface to hand it. That constraint happens
     * to match the desired behaviour exactly: video should stop when the driver leaves the
     * screen, where music should not.
     */
    VIDEO,
}

/**
 * One playable item.
 *
 * [uri] is null for anything we do not own the stream of — a Bluetooth track or a radio
 * station is describable but not openable, and the type says so rather than handing out a URI
 * that would fail at play time.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri?,
    val artworkUri: Uri?,
    val source: MediaSourceId,
    val trackNumber: Int? = null,
) {
    /** "Ed Sheeran · ÷ (Divide)" — the subtitle format the design uses under the title. */
    val subtitle: String
        get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Repeat cycles off → all → one, per docs/04-media.md. */
enum class RepeatMode { OFF, ALL, ONE }

/**
 * Whether a source can be used right now, and what to say when it cannot.
 *
 * The message is part of the data rather than a `when` in the UI because only the source knows
 * why it is unavailable — "Insert USB drive" and "Connect a phone" are not interchangeable.
 */
data class SourceAvailability(
    val id: MediaSourceId,
    val available: Boolean,
    val emptyMessage: String,
)

/**
 * Everything the media UI needs for one frame. One object so the Media tab and the Home widget
 * cannot drift out of sync — they render the same snapshot.
 */
data class PlaybackSnapshot(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val source: MediaSourceId = MediaSourceId.LOCAL,
    val playbackKind: PlaybackKind = PlaybackKind.LOCAL_PLAYER,
    /** False for radio and for Bluetooth peers whose AVRCP profile does not support it. */
    val canSeek: Boolean = true,
    val canSkip: Boolean = true,
) {
    val hasTrack: Boolean get() = track != null

    /** 0f..1f, guarded so a zero-length or streaming item cannot divide by zero. */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Header line for the queue card: "Today's Top Hits" / "38 songs · 2h 21m". */
data class QueueSummary(val title: String, val subtitle: String)
