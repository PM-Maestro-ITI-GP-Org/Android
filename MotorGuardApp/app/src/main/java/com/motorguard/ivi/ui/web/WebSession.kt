package com.motorguard.ivi.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A WebView that outlives the screen showing it.
 *
 * Built for the YouTube pane and generalised when Spotify needed exactly the same behaviour: a
 * site whose *session* is the whole point. Destroying the view on dispose throws away the
 * sign-in and the current page, so every visit restarts logged-out at the home feed — useless
 * for a service you log into once and use for months.
 *
 * Leaving therefore only detaches and pauses. Playback and timers stop, so nothing runs on in
 * the background, but the page, the scroll position and the session survive.
 *
 * The view is created against the **application** context deliberately: one holding an Activity
 * would leak it for exactly as long as this lives, which is the life of the process.
 */
@SuppressLint("StaticFieldLeak")
class WebSession(
    private val homeUrl: String,
    /** Overrides the WebView's own UA where a site refuses it. See [Spotify]. */
    private val userAgent: String? = null,
) {

    private var webView: WebView? = null

    /**
     * How many holders currently have the view on screen.
     *
     * Counted rather than assumed, because Compose composes the *new* holder before disposing
     * the old one. Going inline → full screen runs attach() then detach(), and a detach that
     * paused unconditionally would pause the view the new holder had just adopted — which is
     * how full screen first came up black.
     */
    private var attachments = 0

    /** Whether this site is the one currently selected, kept across fragment recreation. */
    var selected: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    fun acquire(context: Context): WebView {
        webView?.let { return it }

        val view = WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            // Where the sign-in actually lives. Without DOM storage neither site can hold a
            // session at all, and every visit is a logged-out one.
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // Both sites refuse to start playback from script otherwise, which on a touchscreen
            // turns every play into two taps.
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // Keep navigation inside the pane: a hand-off would go to a browser this image does
            // not have, and the tap would appear to do nothing.
            webViewClient = WebViewClient()
            // HTML5 fullscreen inside the page needs this to work at all.
            webChromeClient = WebChromeClient()
            userAgent?.let { settings.userAgentString = it }
            loadUrl(homeUrl)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        webView = view
        return view
    }

    /** Coming into view. */
    fun attach(view: WebView) {
        attachments++
        view.onResume()
        view.resumeTimers()
    }

    /**
     * Leaving a holder. Only really pauses once nothing is showing it any more.
     *
     * Note it does *not* detach the view from its parent: Compose's AndroidView already removes
     * its child on dispose, and doing it here as well steals the view from whichever holder is
     * taking over.
     */
    fun detach(view: WebView) {
        attachments--
        if (attachments > 0) return
        attachments = 0
        view.onPause()
        view.pauseTimers()
        // Cookies carry the login across a process restart and are only written when flushed —
        // a head unit is powered off, not closed politely.
        runCatching { CookieManager.getInstance().flush() }
    }

    companion object {
        /** The mobile site: laid out for a touchscreen and far lighter than the desktop one. */
        val YouTube = WebSession("https://m.youtube.com/")

        /**
         * Spotify's web player.
         *
         * Note this board has no Widevine (`dumpsys media.drm` is empty), so protected playback
         * may refuse. Browsing, search and the account all work regardless, and the moment DRM
         * is added to the image this needs no change.
         */
        val Spotify = WebSession(
            "https://open.spotify.com/",
            // Spotify refuses the stock WebView agent outright — it renders "Unsupported
            // browser" and nothing else, because the string carries the "; wv" marker that
            // identifies an embedded view. The engine underneath *is* Chrome 128, so claiming
            // Chrome is accurate about capability rather than a disguise.
            userAgent = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.6613.88 Mobile Safari/537.36",
        )
    }
}
