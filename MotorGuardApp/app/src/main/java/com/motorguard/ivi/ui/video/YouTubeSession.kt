package com.motorguard.ivi.ui.video

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * One WebView, kept for the life of the process.
 *
 * The pane used to build and `destroy()` a WebView per composition, which meant leaving the
 * Videos tab threw away the sign-in *and* whatever you were watching — every visit started back
 * at the home feed. A browser that forgets itself on every glance at the map is not much use in
 * a car.
 *
 * So the view outlives the screen. Navigating away only detaches and pauses it: playback and
 * timers stop, so nothing keeps running in the background, but the page, the scroll position and
 * the session are all still there when you come back.
 *
 * Built against the **application** context on purpose — a WebView holding an Activity would leak
 * it for the life of the process, which is exactly how long this one lives.
 */
object YouTubeSession {

    private const val HOME_URL = "https://m.youtube.com/"

    @SuppressLint("StaticFieldLeak")
    private var webView: WebView? = null

    /**
     * Which sub-tab the driver last had open.
     *
     * Lives here rather than in the screen because the fragment is replaced on every rail
     * switch, taking its composable state with it — so keeping the WebView alive was only half
     * the fix: coming back still landed on Library, which looks exactly like the session was
     * lost even though it was not.
     */
    var showYouTube: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    fun acquire(context: Context): WebView {
        webView?.let { return it }

        val view = WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            // Where the sign-in actually lives. Without DOM storage YouTube cannot keep a
            // session at all, and every visit is a logged-out one.
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // YouTube will not start playback from script otherwise, which on a touchscreen
            // turns every video into two taps.
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // Keep navigation inside the pane: a hand-off would go to a browser this image does
            // not have, and the tap would appear to do nothing.
            webViewClient = WebViewClient()
            // HTML5 fullscreen inside the page needs this to work at all.
            webChromeClient = WebChromeClient()
            loadUrl(HOME_URL)
        }

        // Cookies are what carry the login across a process restart, and they are only written
        // to disk when flushed — a head unit is usually powered off rather than closed politely.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        webView = view
        return view
    }

    /**
     * How many places currently have the view on screen.
     *
     * Counted rather than assumed, because Compose composes the *new* holder before disposing
     * the old one. Going inline → full screen therefore runs attach() then detach(), and a
     * detach that paused unconditionally would pause the view the new holder had just adopted —
     * which is exactly why full screen came up black.
     */
    private var attachments = 0

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
     * its child on dispose, and doing it here as well is what stole the view from the holder
     * that was taking over.
     */
    fun detach(view: WebView) {
        attachments--
        if (attachments > 0) return
        attachments = 0
        view.onPause()
        view.pauseTimers()
        runCatching { CookieManager.getInstance().flush() }
    }
}
