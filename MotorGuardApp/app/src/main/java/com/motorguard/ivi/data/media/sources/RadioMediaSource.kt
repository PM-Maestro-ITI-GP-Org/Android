package com.motorguard.ivi.data.media.sources

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Radio, as internet streams.
 *
 * The board has no tuner and is not going to get one, so the honest implementation of "Radio" on
 * this hardware is streaming. Stations come from [SomaFm]; each one becomes an ordinary [Track]
 * whose URI is the stream, which means the existing ExoPlayer path plays it with no special
 * handling — the source tab, the queue and the transport bar all keep working as they do for
 * files.
 *
 * [RadioTuner] is still there, unimplemented, as the contract for a board that does have a
 * tuner. Nothing here uses it; the two would sit side by side, with the band picker choosing
 * between them.
 *
 * Availability follows the network rather than the hardware: a head unit parked out of Wi-Fi
 * range has no radio, and the tab should say that instead of listing stations that cannot play.
 */
class RadioMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.RADIO
    override val label = "Radio"
    override val playbackKind = PlaybackKind.STREAM

    override fun availability(): Flow<SourceAvailability> = callbackFlow {
        fun push() {
            trySend(
                SourceAvailability(
                    id = id,
                    available = hasInternet(),
                    emptyMessage = "Connect to Wi-Fi to listen to radio",
                ),
            )
        }

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = push()
            override fun onLost(network: Network) = push()
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = push()
        }
        runCatching {
            manager?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }

        push()
        awaitClose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

    /** The default listing. [search] is what the tab's search field calls. */
    override suspend fun tracks(): List<Track> =
        runCatching { SomaFm.popular().map { it.toTrack() } }.getOrDefault(emptyList())

    /** Stations matching [query]; blank falls back to the default listing. */
    suspend fun search(query: String): List<Track> {
        if (query.isBlank()) return tracks()
        return runCatching { SomaFm.search(query).map { it.toTrack() } }
            .getOrDefault(emptyList())
    }

    /**
     * NET_CAPABILITY_VALIDATED, not merely CONNECTED: a Wi-Fi network the car has joined but
     * which has no route out — a captive portal in a car park being the usual case — would
     * otherwise read as available and every station would fail to open.
     */
    private fun hasInternet(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * A station as a [Track].
     *
     * `durationMs` is 0 and stays 0 — a live stream has no length, and the snapshot's `progress`
     * already guards against dividing by it. The station name goes in `title` and the genre and
     * country in `artist`, because that is what the now-playing card and the queue row read.
     */
    private fun OnlineStation.toTrack() = Track(
        id = "radio:$uuid",
        title = name,
        artist = subtitle,
        album = "Radio",
        durationMs = 0L,
        uri = Uri.parse(streamUrl),
        artworkUri = faviconUrl?.let { runCatching { Uri.parse(it) }.getOrNull() },
        source = id,
    )
}
