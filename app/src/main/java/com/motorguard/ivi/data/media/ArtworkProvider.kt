package com.motorguard.ivi.data.media

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds cover art for a track that has none embedded.
 *
 * Most files ripped from CD, and nearly every file shared over a messaging app, carry no artwork
 * at all — on a real 541-track library the local lookup misses far more often than it hits. This
 * is the fallback, behind an interface so the lookup service can be swapped the same way the
 * navigation providers can.
 */
interface ArtworkProvider {

    /**
     * @return raw image bytes, or null when nothing matched. Null is a normal outcome, not an
     *         error — plenty of tracks genuinely have no findable cover.
     */
    suspend fun findArtwork(artist: String, album: String, title: String): ByteArray?
}

/**
 * Cover lookup via the **iTunes Search API**.
 *
 * Chosen because it needs no API key, no registration and no quota negotiation — the same
 * property that made Photon and OpenFreeMap the right calls for navigation — and it answers in a
 * single request with the best coverage of the free options.
 *
 * The open alternative is MusicBrainz + Cover Art Archive: fully OSS, but it needs two requests
 * (search for a release MBID, then fetch `coverartarchive.org/release/{mbid}/front-500`), holds
 * you to one request per second, and has noticeably thinner coverage. If the licence of the
 * lookup service ever matters more than hit rate, that is the swap — implement this same
 * interface and change the one line in [MediaConfig].
 */
class ITunesArtworkProvider : ArtworkProvider {

    override suspend fun findArtwork(artist: String, album: String, title: String): ByteArray? {
        // Album beats track title: one lookup then serves every track on the record, and album
        // names match the store's catalogue far more reliably than individual song titles.
        val term = listOf(artist, album.ifBlank { title })
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (term.length < MIN_TERM) return null

        val entity = if (album.isNotBlank()) "album" else "musicTrack"
        val url = buildString {
            append(MediaConfig.artworkSearchUrl)
            append("?term=").append(URLEncoder.encode(term, "UTF-8"))
            append("&entity=").append(entity)
            append("&limit=1")
        }

        val response = runCatching { getString(url) }.getOrNull() ?: return null
        val results = runCatching { JSONObject(response).optJSONArray("results") }.getOrNull()
        val first = results?.optJSONObject(0) ?: return null

        val thumbnail = first.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: return null
        // The API only ever returns a 100 px thumbnail, but the same path serves any size — this
        // substitution is the documented way to get something usable on a 1920-wide dashboard.
        val fullSize = thumbnail.replace("100x100", "${MediaConfig.artworkPixels}x${MediaConfig.artworkPixels}")

        return runCatching { getBytes(fullSize) }.getOrNull()
    }

    private fun getString(url: String): String = openConnection(url).use { it.readBytes().decodeToString() }

    private fun getBytes(url: String): ByteArray = openConnection(url).use { it.readBytes() }

    private fun openConnection(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", MediaConfig.userAgent)
        if (responseCode !in 200..299) {
            disconnect()
            throw IOException("HTTP $responseCode from ${URL(url).host}")
        }
        inputStream
    }

    private companion object {
        const val MIN_TERM = 3
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

/** Media knobs, mirroring `NavConfig`'s role for navigation. */
object MediaConfig {

    /** Turn the network lookup off entirely — offline builds, or a data-cost policy. */
    var onlineArtwork: Boolean = true

    /** Swap for another [ArtworkProvider] to change lookup service. */
    var artworkProvider: ArtworkProvider = ITunesArtworkProvider()

    var artworkSearchUrl: String = "https://itunes.apple.com/search"

    /** Requested cover size. 600 is sharp on a 1920-wide panel without being wasteful. */
    var artworkPixels: Int = 600

    var userAgent: String = "MotorGuardIVI/0.1 (AAOS)"
}
