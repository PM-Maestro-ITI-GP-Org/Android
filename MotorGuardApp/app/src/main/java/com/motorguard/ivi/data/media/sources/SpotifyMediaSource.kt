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
 * Spotify, as its web player.
 *
 * There is no app to remote-control: Spotify is not installed and this image has no Play Store
 * to install it from, so the web player is the only route that exists today. Playback happens
 * inside the page, which is why [tracks] is empty and [playbackKind] is [PlaybackKind.WEB] —
 * this app is not holding the stream and must not pretend its transport bar drives one.
 *
 * **Caveat worth knowing.** The board reports no Widevine (`dumpsys media.drm` is empty), so
 * protected playback may be refused by Spotify even though browsing, search and sign-in work.
 * Nothing here changes if DRM is later added to the image.
 */
class SpotifyMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.SPOTIFY
    override val label = "Spotify"
    override val playbackKind = PlaybackKind.WEB

    override fun availability(): Flow<SourceAvailability> = callbackFlow {
        fun push() {
            trySend(
                SourceAvailability(
                    id = id,
                    available = hasInternet(),
                    emptyMessage = "Connect to Wi-Fi to use Spotify",
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
