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
 * YouTube Music, as its web player — replaces Spotify and Anghami as the free on-demand music
 * source. Both of those gate full playback behind an account: Spotify's free web tier redirects
 * straight to its Premium subscription upsell the moment play is tapped, and Anghami's "Play for
 * free" redirects to the Play Store to push its native app instead of playing anything, neither
 * of which this image can act on (no app store, no purchase flow here). YouTube's own player
 * (the plain video site, [YouTube]) was confirmed live to play on demand with no account and no
 * redirect — this is the same site, at its music-focused address, for a proper now-playing bar
 * and search built for songs rather than video results.
 *
 * Same architecture as the sources it replaces: the page owns the audio, the queue and the
 * transport, so [tracks] is empty and [playbackKind] is [PlaybackKind.WEB].
 */
class YouTubeMusicMediaSource(private val context: Context) : MediaLibrarySource {

    override val id = MediaSourceId.YOUTUBE_MUSIC
    override val label = "YouTube Music"
    override val playbackKind = PlaybackKind.WEB

    override fun availability(): Flow<SourceAvailability> = callbackFlow {
        fun push() {
            trySend(
                SourceAvailability(
                    id = id,
                    available = hasInternet(),
                    emptyMessage = "Connect to Wi-Fi to use YouTube Music",
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
