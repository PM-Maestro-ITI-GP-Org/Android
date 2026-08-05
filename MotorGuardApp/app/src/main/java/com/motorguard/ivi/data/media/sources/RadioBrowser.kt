package com.motorguard.ivi.data.media.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * One internet radio station, as the directory describes it.
 *
 * Distinct from [RadioStation] in [RadioTuner], which is a *dial position* — a frequency and an
 * RDS name. A stream has neither, and conflating the two would mean carrying a meaningless
 * frequency around for every station in the list.
 */
data class OnlineStation(
    val uuid: String,
    val name: String,
    val streamUrl: String,
    val faviconUrl: String?,
    val country: String,
    val tags: String,
    val bitrate: Int,
) {
    /** "Jazz · Netherlands · 128 kbps" — the subtitle the station rows show. */
    val subtitle: String
        get() = listOf(
            tags.split(',').firstOrNull()?.trim()?.replaceFirstChar { it.uppercase() }.orEmpty(),
            country,
            if (bitrate > 0) "$bitrate kbps" else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * Internet radio via the radio-browser.info directory.
 *
 * The Pi has no tuner and there is no realistic prospect of one, so "radio" here means streams.
 * This directory is the obvious backend: community-run, no API key, no quota, and it already
 * has the search endpoint the feature needs. See [RadioTuner] for the hardware contract that is
 * still there for a board that does have a tuner.
 *
 * Two things the API asks of clients and that are easy to get wrong:
 *  - It insists on a descriptive `User-Agent`; requests without one are rejected.
 *  - It is a pool of volunteer mirrors behind one name, and individual mirrors do go down, so a
 *    failure against one host is retried against the next rather than surfaced as "no stations".
 */
internal object RadioBrowser {

    /**
     * Mirrors, tried in order.
     *
     * `all.api` round-robins across the pool via DNS, which is the documented entry point;
     * the named mirrors are the fallback for a resolver that returns a dead one.
     */
    private val HOSTS = listOf(
        "https://all.api.radio-browser.info",
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
    )

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /** The directory rejects anonymous clients, so this is required, not decorative. */
    private const val USER_AGENT = "MotorGuardIVI/0.1 (+aaos-head-unit)"

    /**
     * Stations matching [query], most-voted first.
     *
     * `hidebroken` is what keeps the list usable — the directory knows which streams last failed
     * its checks, and a car is the worst place to discover a dead URL by tapping it.
     */
    suspend fun search(query: String, limit: Int = 60): List<OnlineStation> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetch(
            "/json/stations/search?name=$encoded&limit=$limit" +
                "&hidebroken=true&order=votes&reverse=true",
        )
    }

    /** The default listing, for a tab opened without a search. */
    suspend fun popular(limit: Int = 60): List<OnlineStation> =
        fetch("/json/stations/search?limit=$limit&hidebroken=true&order=votes&reverse=true")

    private suspend fun fetch(path: String): List<OnlineStation> = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (host in HOSTS) {
            try {
                return@withContext parse(getString(host + path))
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No radio directory host responded")
    }

    private fun getString(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Internal rather than private so the field fallbacks and de-duplication can be tested. */
    internal fun parse(json: String): List<OnlineStation> {
        val array = JSONArray(json)
        val out = ArrayList<OnlineStation>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue

            // url_resolved has already followed the playlist indirection (.pls/.m3u) that a lot
            // of "url" values are. ExoPlayer can handle some of those itself, but not all, so the
            // resolved form is preferred wherever the directory has one.
            val stream = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (stream.isBlank() || name.isBlank()) continue

            out.add(
                OnlineStation(
                    uuid = o.optString("stationuuid").ifBlank { stream },
                    name = name,
                    streamUrl = stream,
                    faviconUrl = o.optString("favicon").takeIf { it.isNotBlank() },
                    country = o.optString("country").trim(),
                    tags = o.optString("tags").trim(),
                    bitrate = o.optInt("bitrate"),
                ),
            )
        }
        // The directory happily lists the same station under several entries; the driver is
        // choosing something to listen to, not auditing the database.
        return out.distinctBy { it.name.lowercase() }
    }
}
