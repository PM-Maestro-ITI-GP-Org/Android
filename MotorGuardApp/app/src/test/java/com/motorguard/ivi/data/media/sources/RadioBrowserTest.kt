package com.motorguard.ivi.data.media.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directory is a community database, so its rows are not uniform: fields go missing, streams
 * come back blank, and the same station is listed several times over. Every case here is one that
 * turns up in a real response.
 */
class RadioBrowserTest {

    @Test
    fun `prefers url_resolved over url`() {
        val stations = RadioBrowser.parse(
            """
            [{"stationuuid":"a","name":"Jazz FM",
              "url":"http://example.com/listen.pls",
              "url_resolved":"http://example.com/stream.mp3"}]
            """.trimIndent(),
        )
        // The raw "url" is often a .pls playlist that ExoPlayer cannot always follow.
        assertEquals("http://example.com/stream.mp3", stations.single().streamUrl)
    }

    @Test
    fun `falls back to url when url_resolved is absent`() {
        val stations = RadioBrowser.parse(
            """[{"stationuuid":"a","name":"Jazz FM","url":"http://example.com/stream.mp3"}]""",
        )
        assertEquals("http://example.com/stream.mp3", stations.single().streamUrl)
    }

    @Test
    fun `drops rows with no playable stream or no name`() {
        val stations = RadioBrowser.parse(
            """
            [{"stationuuid":"a","name":"No Stream","url":"","url_resolved":""},
             {"stationuuid":"b","name":"   ","url_resolved":"http://example.com/s"},
             {"stationuuid":"c","name":"Good","url_resolved":"http://example.com/good"}]
            """.trimIndent(),
        )
        assertEquals(listOf("Good"), stations.map { it.name })
    }

    @Test
    fun `de-duplicates stations listed more than once`() {
        val stations = RadioBrowser.parse(
            """
            [{"stationuuid":"a","name":"Jazz FM","url_resolved":"http://a/1"},
             {"stationuuid":"b","name":"JAZZ FM","url_resolved":"http://b/2"},
             {"stationuuid":"c","name":"Blues FM","url_resolved":"http://c/3"}]
            """.trimIndent(),
        )
        assertEquals(listOf("Jazz FM", "Blues FM"), stations.map { it.name })
    }

    @Test
    fun `missing optional fields do not drop the station`() {
        val station = RadioBrowser.parse(
            """[{"name":"Bare","url_resolved":"http://example.com/s"}]""",
        ).single()

        assertNull(station.faviconUrl)
        assertEquals("", station.country)
        assertEquals(0, station.bitrate)
        // With no uuid the stream URL stands in, so the Track id is still unique and stable.
        assertEquals("http://example.com/s", station.uuid)
    }

    @Test
    fun `subtitle uses the first tag and omits what is missing`() {
        val station = RadioBrowser.parse(
            """
            [{"name":"S","url_resolved":"http://e/s",
              "tags":"smooth jazz,easy listening","country":"Netherlands","bitrate":128}]
            """.trimIndent(),
        ).single()
        assertEquals("Smooth jazz · Netherlands · 128 kbps", station.subtitle)

        val sparse = RadioBrowser.parse(
            """[{"name":"S","url_resolved":"http://e/s","country":"Egypt"}]""",
        ).single()
        assertEquals("Egypt", sparse.subtitle)
    }

    @Test
    fun `an empty response is empty rather than an error`() {
        assertTrue(RadioBrowser.parse("[]").isEmpty())
    }
}
