package com.motorguard.ivi.ui.voice

import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackSnapshot
import com.motorguard.ivi.data.media.RepeatMode
import com.motorguard.ivi.data.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which playback command was meant, and what the answer sounds like. */
class MediaVoiceTest {

    private fun track(title: String = "Ya Msafer", artist: String = "Mohamed Mounir") =
        Track(
            id = "1", title = title, artist = artist, album = "", durationMs = 1_000L,
            // Uri is a platform stub in unit tests; null is a real value here and keeps the
            // sentence-under-test free of anything Android has to provide.
            uri = null, artworkUri = null, source = MediaSourceId.LOCAL,
        )

    private fun snap(
        track: Track? = track(),
        playing: Boolean = true,
    ) = PlaybackSnapshot(track = track, isPlaying = playing)

    private fun ask(u: String) = MediaVoice.intentOf(u)

    @Test
    fun `transport commands are recognised`() {
        assertEquals(MediaVoice.Ask.PAUSE, ask("pause"))
        assertEquals(MediaVoice.Ask.PAUSE, ask("stop the music"))
        assertEquals(MediaVoice.Ask.PLAY, ask("play"))
        assertEquals(MediaVoice.Ask.PLAY, ask("resume"))
        assertEquals(MediaVoice.Ask.NEXT, ask("next track"))
        assertEquals(MediaVoice.Ask.NEXT, ask("skip this song"))
        assertEquals(MediaVoice.Ask.PREVIOUS, ask("previous track"))
        assertEquals(MediaVoice.Ask.SHUFFLE, ask("shuffle"))
        assertEquals(MediaVoice.Ask.REPEAT, ask("repeat"))
    }

    @Test
    fun `volume commands are recognised`() {
        assertEquals(MediaVoice.Ask.VOLUME_UP, ask("turn it up"))
        assertEquals(MediaVoice.Ask.VOLUME_UP, ask("louder"))
        assertEquals(MediaVoice.Ask.VOLUME_DOWN, ask("turn the music down"))
        assertEquals(MediaVoice.Ask.VOLUME_DOWN, ask("too loud"))
        assertEquals(MediaVoice.Ask.MUTE, ask("mute"))
        assertEquals(MediaVoice.Ask.UNMUTE, ask("unmute"))
    }

    /**
     * "Turn the music up" contains "music" and used to be a Media route anchor. Volume has to win,
     * or the loudest thing in the cabin gets a tab opened at it instead of being turned down.
     */
    @Test
    fun `volume beats the browse phrasings it shares words with`() {
        assertEquals(MediaVoice.Ask.VOLUME_UP, ask("turn the music up"))
        assertEquals(MediaVoice.Ask.VOLUME_DOWN, ask("turn the music down"))
    }

    /** "Unmute" contains "mute". Longest intent first is what keeps them apart. */
    @Test
    fun `unmute is not read as mute`() {
        assertEquals(MediaVoice.Ask.UNMUTE, ask("unmute the music"))
    }

    /**
     * Browsing is still the tab's job. A bare "play" is a transport command; "play some music" is
     * a request to be shown something to play, and claiming it here would leave the driver with a
     * player that has nothing queued and an assistant that said "Playing."
     */
    @Test
    fun `browse phrasings are left to the route`() {
        assertNull(ask("play some music"))
        assertNull(ask("put something on"))
        assertNull(ask("play my playlist"))
    }

    @Test
    fun `leaves everything else alone`() {
        listOf("take me home", "call mona", "is there a fault in the motor", "", "what time is it")
            .forEach { assertNull(it, ask(it)) }
    }

    // --- what it says --------------------------------------------------------

    @Test
    fun `now playing names the track and the artist`() {
        assertEquals("This is Ya Msafer, by Mohamed Mounir.", MediaVoice.nowPlaying(snap()))
    }

    @Test
    fun `now playing says so when it is paused`() {
        assertTrue(MediaVoice.nowPlaying(snap(playing = false)).startsWith("Paused on"))
    }

    @Test
    fun `now playing with nothing loaded is not a fabricated title`() {
        assertEquals("Nothing's playing.", MediaVoice.nowPlaying(snap(track = null)))
    }

    /** Radio streams routinely arrive with no artist; the sentence must not trail off. */
    @Test
    fun `a missing artist is left out rather than left blank`() {
        val reply = MediaVoice.nowPlaying(snap(track = track(artist = "")))
        assertEquals("This is Ya Msafer.", reply)
    }

    /** The reply names the mode being moved to, matching MediaConnection's off to all to one. */
    @Test
    fun `repeat cycles the way the player cycles`() {
        assertEquals(RepeatMode.ALL, MediaVoice.nextRepeat(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, MediaVoice.nextRepeat(RepeatMode.ALL))
        assertEquals(RepeatMode.OFF, MediaVoice.nextRepeat(RepeatMode.ONE))
        assertEquals("Repeating this track.", MediaVoice.repeatReply(RepeatMode.ONE))
    }
}
