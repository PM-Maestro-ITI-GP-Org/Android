package com.motorguard.ivi.data.media

import com.motorguard.ivi.ui.media.components.formatTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaModelsTest {

    private fun track(artist: String = "Ed Sheeran", album: String = "÷ (Divide)") = Track(
        id = "t",
        title = "Shape of You",
        artist = artist,
        album = album,
        durationMs = 233_000,
        uri = null,
        artworkUri = null,
        source = MediaSourceId.LOCAL,
    )

    @Test
    fun `subtitle joins artist and album the way the design shows it`() {
        assertEquals("Ed Sheeran · ÷ (Divide)", track().subtitle)
    }

    @Test
    fun `subtitle drops missing tags instead of leaving separators`() {
        assertEquals("Ed Sheeran", track(album = "").subtitle)
        assertEquals("÷ (Divide)", track(artist = "").subtitle)
        assertEquals("", track(artist = "", album = "").subtitle)
    }

    @Test
    fun `progress is guarded against a zero or unknown duration`() {
        // A stream, or a track whose duration has not resolved yet — must not divide by zero.
        assertEquals(0f, PlaybackSnapshot(positionMs = 5_000, durationMs = 0).progress, 0f)
        assertEquals(0f, PlaybackSnapshot(positionMs = 5_000, durationMs = -1).progress, 0f)
    }

    @Test
    fun `progress clamps when position runs past duration`() {
        val snapshot = PlaybackSnapshot(positionMs = 300_000, durationMs = 233_000)
        assertEquals(1f, snapshot.progress, 0f)
    }

    @Test
    fun `progress is the expected fraction`() {
        assertEquals(0.5f, PlaybackSnapshot(positionMs = 50, durationMs = 100).progress, 0.001f)
    }

    @Test
    fun `time formatting matches the design's labels`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:00", formatTime(-1))
        assertEquals("2:09", formatTime(129_000))
        assertEquals("5:23", formatTime(323_000))
        // Long recordings roll over into hours rather than showing "132:15".
        assertEquals("2:12:15", formatTime(7_935_000))
    }
}
