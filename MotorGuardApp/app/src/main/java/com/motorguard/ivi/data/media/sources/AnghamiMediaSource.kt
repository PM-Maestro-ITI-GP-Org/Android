package com.motorguard.ivi.data.media.sources

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Anghami, as its web player — offered alongside Spotify for the same reason a driver is asked
 * for a second opinion rather than one: Spotify's web player gates full playback behind a
 * Premium subscription (confirmed live — tapping play on a free account redirects the whole page
 * to its upsell rather than playing anything), where Anghami's free tier is ad-supported and
 * plays without that wall. Same architecture as [SpotifyMediaSource] in every other respect: the
 * page owns the audio, the queue and the transport, so [tracks] is empty and [playbackKind] is
 * [PlaybackKind.WEB].
 */
class AnghamiMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.ANGHAMI
    override val label = "Anghami"
    override val playbackKind = PlaybackKind.WEB

    override fun availability(): Flow<SourceAvailability> = callbackFlow {
        fun push() {
            trySend(
                SourceAvailability(
                    id = id,
                    available = hasInternet(),
                    emptyMessage = "Connect to Wi-Fi to use Anghami",
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

    /** The page is the library; there is nothing for our queue to list. */
    override suspend fun tracks(): List<Track> = emptyList()

    /** VALIDATED, not merely CONNECTED — a captive portal would otherwise read as usable. */
    private fun hasInternet(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
