package com.motorguard.ivi.data.media.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
 * Internet radio via SomaFM.
 *
 * Replaces the earlier radio-browser.info-backed directory: that service is a crowd-submitted
 * pool of tens of thousands of stations, and "crowd-submitted" means a meaningful fraction are
 * dead, geo-blocked, or simply gone the week after someone added them — exactly the kind of thing
 * a driver should not discover by tapping a station and getting silence. SomaFM has run the same
 * few dozen curated, staffed channels continuously for two decades; far fewer stations, but every
 * one of them is real and actually up.
 *
 * `channels.json` lists each station's `.pls` playlist URLs rather than a raw stream URL — SomaFM
 * load-balances across several `iceN.somafm.com` mirrors and picks one per request, so the only
 * way to get a URL actually worth handing to ExoPlayer is to resolve the `.pls` live, the same
 * "prefer the resolved form" reasoning the old radio-browser.info client used for the same reason.
 * Resolved once per process and cached: the channel list itself changes rarely enough that
 * re-resolving all 46 on every tab reopen would be pure waste.
 */
internal object SomaFm {

    private const val CHANNELS_URL = "https://somafm.com/channels.json"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val USER_AGENT = "MotorGuardIVI/0.1 (+aaos-head-unit)"

    /** Resolving 46 playlists at once against one host is asking to be throttled; a handful of
     *  requests are read, most were reused, is the honest description of it either way. */
    private const val MAX_CONCURRENT_RESOLVES = 8

    @Volatile
    private var cache: List<OnlineStation>? = null

    /** The full, static channel list — there is no "popular" ordering to ask SomaFM for. */
    suspend fun popular(): List<OnlineStation> = channels()

    /** Client-side filter over the same small list: 46 curated channels do not need a server
     *  search endpoint, and this saves the round trip entirely once the first fetch has landed. */
    suspend fun search(query: String): List<OnlineStation> {
        val all = channels()
        val q = query.trim()
        if (q.isBlank()) return all
        return all.filter {
            it.name.contains(q, ignoreCase = true) || it.tags.contains(q, ignoreCase = true)
        }
    }

    private suspend fun channels(): List<OnlineStation> {
        cache?.let { return it }
        val fetched = runCatching { fetchAndResolve() }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) cache = fetched
        return fetched
    }

    private suspend fun fetchAndResolve(): List<OnlineStation> = withContext(Dispatchers.IO) {
        val listing = parseChannelList(getString(CHANNELS_URL))
        val gate = Semaphore(MAX_CONCURRENT_RESOLVES)
        coroutineScope {
            listing
                .map { channel -> async { gate.withPermit { resolve(channel) } } }
                .awaitAll()
                .filterNotNull()
        }
    }

    /** What `channels.json` gives before its playlist indirection is resolved. */
    internal data class ChannelListing(
        val id: String,
        val title: String,
        val genre: String,
        val image: String?,
        val playlistUrl: String,
    )

    /** Internal rather than private so the field fallbacks and quality preference can be
     *  tested without a live fetch. */
    internal fun parseChannelList(json: String): List<ChannelListing> {
        val channels = JSONObject(json).optJSONArray("channels") ?: JSONArray()
        val out = ArrayList<ChannelListing>(channels.length())
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val title = c.optString("title").trim()
            if (id.isBlank() || title.isBlank()) continue

            val playlists = c.optJSONArray("playlists") ?: continue
            // Highest-quality mp3 first: the widest device/codec compatibility of the formats on
            // offer. Any playlist beats none, so the first entry is the fallback rather than
            // skipping a channel whose lineup happens not to include mp3.
            var chosen: String? = null
            for (j in 0 until playlists.length()) {
                val p = playlists.optJSONObject(j) ?: continue
                val url = p.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (chosen == null) chosen = url
                if (p.optString("format") == "mp3" && p.optString("quality") == "highest") {
                    chosen = url
                    break
                }
            }
            val playlistUrl = chosen ?: continue

            out.add(
                ChannelListing(
                    id = id,
                    title = title,
                    genre = c.optString("genre").trim(),
                    image = c.optString("image").takeIf { it.isNotBlank() },
                    playlistUrl = playlistUrl,
                ),
            )
        }
        return out
    }

    /** One channel's `.pls` resolved to the raw stream URL its first, best-ranked entry names —
     *  null (dropped, not surfaced) if that mirror is unreachable right now. */
    private fun resolve(channel: ChannelListing): OnlineStation? {
        val pls = runCatching { getString(channel.playlistUrl) }.getOrNull() ?: return null
        val streamUrl = parsePls(pls) ?: return null
        val bitrate = Regex("-(\\d+)-[a-z]+$").find(streamUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return OnlineStation(
            uuid = channel.id,
            name = channel.title,
            streamUrl = streamUrl,
            faviconUrl = channel.image,
            country = "",
            tags = channel.genre,
            bitrate = bitrate,
        )
    }

    /** `File1=` out of a PLS file — SomaFM's load balancer already ordered the mirrors, so the
     *  first is the one worth using. Internal so this is testable without a live fetch. */
    internal fun parsePls(pls: String): String? =
        pls.lineSequence()
            .firstOrNull { it.startsWith("File1=") }
            ?.removePrefix("File1=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun getString(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
