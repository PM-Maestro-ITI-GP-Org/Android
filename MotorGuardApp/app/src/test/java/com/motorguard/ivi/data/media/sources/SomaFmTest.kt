package com.motorguard.ivi.data.media.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SomaFmTest {

    @Test
    fun `prefers highest-quality mp3 over other formats`() {
        val listing = SomaFm.parseChannelList(
            """
            {"channels":[{"id":"a","title":"Groove Salad","genre":"ambient",
              "image":"http://img/a.png",
              "playlists":[
                {"url":"http://e/a-64-aacp.pls","format":"aacp","quality":"low"},
                {"url":"http://e/a-128-mp3.pls","format":"mp3","quality":"highest"},
                {"url":"http://e/a-130-aac.pls","format":"aac","quality":"highest"}
              ]}]}
            """.trimIndent(),
        )
        assertEquals("http://e/a-128-mp3.pls", listing.single().playlistUrl)
    }

    @Test
    fun `falls back to the first playlist when no highest mp3 exists`() {
        val listing = SomaFm.parseChannelList(
            """
            {"channels":[{"id":"a","title":"Drone Zone","genre":"ambient",
              "image":"http://img/a.png",
              "playlists":[
                {"url":"http://e/a-130-aac.pls","format":"aac","quality":"highest"},
                {"url":"http://e/a-64-aacp.pls","format":"aacp","quality":"low"}
              ]}]}
            """.trimIndent(),
        )
        assertEquals("http://e/a-130-aac.pls", listing.single().playlistUrl)
    }

    @Test
    fun `drops channels with no id, no title, or no playlists`() {
        val listing = SomaFm.parseChannelList(
            """
            {"channels":[
              {"id":"","title":"No Id","genre":"","playlists":[{"url":"http://e/1","format":"mp3","quality":"highest"}]},
              {"id":"b","title":"","genre":"","playlists":[{"url":"http://e/2","format":"mp3","quality":"highest"}]},
              {"id":"c","title":"No Playlists","genre":"","playlists":[]},
              {"id":"d","title":"Good","genre":"electronic","playlists":[{"url":"http://e/4","format":"mp3","quality":"highest"}]}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("Good"), listing.map { it.title })
    }

    @Test
    fun `missing image does not drop the channel`() {
        val listing = SomaFm.parseChannelList(
            """
            {"channels":[{"id":"a","title":"Bare","genre":"",
              "playlists":[{"url":"http://e/1","format":"mp3","quality":"highest"}]}]}
            """.trimIndent(),
        )
        assertNull(listing.single().image)
    }

    @Test
    fun `an empty channel list is empty rather than an error`() {
        assertTrue(SomaFm.parseChannelList("""{"channels":[]}""").isEmpty())
    }

    @Test
    fun `parsePls takes File1, SomaFM's own best-ranked mirror`() {
        val url = SomaFm.parsePls(
            """
            [playlist]
            numberofentries=2
            File1=https://ice2.somafm.com/groovesalad-128-mp3
            Title1=SomaFM: Groove Salad (#1)
            File2=https://ice6.somafm.com/groovesalad-128-mp3
            Title2=SomaFM: Groove Salad (#2)
            Version=2
            """.trimIndent(),
        )
        assertEquals("https://ice2.somafm.com/groovesalad-128-mp3", url)
    }

    @Test
    fun `parsePls is null when File1 is missing or blank`() {
        assertNull(SomaFm.parsePls("[playlist]\nnumberofentries=0\nVersion=2"))
        assertNull(SomaFm.parsePls("File1=\nVersion=2"))
    }
}
