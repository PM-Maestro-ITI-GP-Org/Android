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
    /** Which media source this page reports as, for the now-playing card. */
    private val sourceId: com.motorguard.ivi.data.media.MediaSourceId,
    /** Overrides the WebView's own UA on a site that refuses the stock one — unused today, kept
     *  for the next source that needs it. */
    private val userAgent: String? = null,
    /**
     * Whether leaving the tab should stop the page.
     *
     * True for video, where walking away from a film should stop it. False for music: YouTube
     * Music is a media source like any other, and a source that stopped the moment the driver
     * looked at the map would be useless — it also has to keep playing for the now-playing card
     * to have anything to show.
     */
    private val pauseOnLeave: Boolean = true,
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

        // userdebug-only diagnostic: lets `chrome://inspect` (or a raw CDP client over
        // `adb forward`) attach to these pages, which is how the Spotify now-playing detection
        // above was actually debugged rather than guessed at a second time.
        WebView.setWebContentsDebuggingEnabled(true)

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
            // Injects the metadata watcher on every navigation, because a single-page app
            // replaces its player without ever firing another page load.
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(WATCHER_JS, null)
                }

                /**
                 * A site that thinks it is being viewed on mobile routinely redirects itself to
                 * an `intent://` deep link the moment it loads — Anghami's home page does this
                 * unconditionally, aiming the driver at its native app. There is no such app on
                 * this image, and letting WebView load the URL verbatim fails outright
                 * (`ERR_UNKNOWN_URL_SCHEME`), stranding the pane on Chromium's own error page
                 * instead of the site.
                 *
                 * Chrome's `intent://` syntax carries a `browser_fallback_url` extra for exactly
                 * this case — the web page to show when the target app is not installed. Follow
                 * it when present; otherwise return to the pane's own home page rather than a
                 * dead end.
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    if (url.scheme == "http" || url.scheme == "https") return false
                    val fallback = runCatching {
                        android.content.Intent
                            .parseUri(url.toString(), android.content.Intent.URI_INTENT_SCHEME)
                            .getStringExtra("browser_fallback_url")
                    }.getOrNull()
                    view.loadUrl(fallback ?: homeUrl)
                    return true
                }
            }
            addJavascriptInterface(Bridge(sourceId), "MotorGuard")
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

    /**
     * The page's own report of what it is playing.
     *
     * `@JavascriptInterface` is the only channel out of a WebView, and it is a real security
     * boundary — the page can call anything annotated here. So this exposes exactly one method
     * that accepts only strings and a boolean, and does nothing but forward them.
     */
    private class Bridge(private val sourceId: com.motorguard.ivi.data.media.MediaSourceId) {
        @android.webkit.JavascriptInterface
        fun nowPlaying(title: String?, artist: String?, album: String?, playing: Boolean) {
            WebPlayback.report(
                source = sourceId,
                title = title.orEmpty(),
                artist = artist.orEmpty(),
                album = album.orEmpty(),
                playing = playing,
            )
        }
    }

    /**
     * Best-effort remote play/pause, for the Home now-playing card's transport row.
     *
     * The page owns the transport (see the class KDoc on why [canSeek]/[canSkip] are false for
     * [com.motorguard.ivi.data.media.PlaybackKind.WEB]), but a plain HTML5 `<video>`/`<audio>`
     * element is a standard, stable target to toggle from outside — unlike guessing at a site's
     * own button markup, which breaks the moment its DOM changes. Both YouTube sites play
     * through one; a site that does not expose one at all makes this a harmless no-op, and its
     * own page remains the only way to control it.
     */
    fun togglePlayback() {
        webView?.evaluateJavascript(TOGGLE_PLAYBACK_JS, null)
    }

    /**
     * Best-effort remote skip, for sources whose page keeps a real queue — YouTube Music's player
     * bar always has next/previous buttons, unlike a plain video page. Clicking the page's own
     * button rather than reimplementing the queue is what keeps this correct as the site's own
     * shuffle/autoplay/radio-mix logic decides what "next" means.
     */
    fun skipNext() {
        webView?.evaluateJavascript(NEXT_TRACK_JS, null)
    }

    fun skipPrevious() {
        webView?.evaluateJavascript(PREVIOUS_TRACK_JS, null)
    }

    /** Coming into view. */
    fun attach(view: WebView) {
        attachments++
        view.onResume()
    }

    /**
     * Leaving a holder. Only really pauses once nothing is showing it any more.
     *
     * Note it does *not* detach the view from its parent: Compose's AndroidView already removes
     * its child on dispose, and doing it here as well steals the view from whichever holder is
     * taking over.
     *
     * [WebView.pauseTimers]/[WebView.resumeTimers] are deliberately never called: despite being
     * instance methods, the platform documents them as pausing JavaScript timers for **every**
     * WebView in the process, not just this one. Leaving the YouTube tab (`pauseOnLeave = true`)
     * was therefore also silencing Spotify's background metadata poll (`pauseOnLeave = false`,
     * so it never asked to be paused at all) — confirmed live: exactly one now-playing report
     * ever arrived, right after Spotify's page first loaded, and none after the driver visited
     * Video and left it. `onPause()` alone is per-instance and covers the intent — reduced
     * rendering/media load on the page that was actually left — without the global side effect.
     */
    fun detach(view: WebView) {
        attachments--
        if (attachments > 0) return
        attachments = 0
        if (pauseOnLeave) {
            view.onPause()
            // Stopped, so it must stop claiming the now-playing card too.
            WebPlayback.clear(sourceId)
        }
        // Cookies carry the login across a process restart and are only written when flushed —
        // a head unit is powered off, not closed politely.
        runCatching { CookieManager.getInstance().flush() }
    }

    companion object {
        /**
         * Watches the page's Media Session metadata and reports changes back.
         *
         * Polled rather than event-driven: the Media Session API fires no change event, and
         * both sites are single-page apps that swap tracks without any navigation. One second is
         * far below what a driver notices and costs nothing measurable.
         *
         * Only *changes* cross the bridge, so a paused page is silent rather than chattering
         * once a second.
         */
        private val WATCHER_JS = """
            (function () {
              if (window.__mgWatch) return;
              window.__mgWatch = true;
              var last = '';
              setInterval(function () {
                try {
                  var ms = navigator.mediaSession;
                  var m = ms && ms.metadata;
                  // <audio> as well as <video>: not every site's player is a <video> element.
                  var media = document.querySelector('video, audio');
                  // YouTube Music never fills in navigator.mediaSession, so its own persistent
                  // player bar is the only place a clean title/artist exist separately —
                  // otherwise this falls all the way to document.title, which glues them
                  // together (e.g. "Toba Toba | YouTube Music") with no way to split them back
                  // apart. Same trick this used for Spotify's bar before it was dropped.
                  var bar = document.querySelector('ytmusic-player-bar');
                  var barTitle = bar && bar.querySelector('.title');
                  var barByline = bar && bar.querySelector('.byline');
                  // Fall back to the media element and the document title: not every site
                  // populates the Media Session API, but there is always an element and a
                  // title once something is playing.
                  var t = (m && m.title) || (barTitle && barTitle.textContent.trim()) ||
                    (media && !media.paused ? document.title.replace(/ - YouTube( Music)?$/, '') : '');
                  var a = (m && m.artist) || (barByline && barByline.textContent.trim().split(' • ')[0]) || '';
                  var al = (m && m.album) || '';
                  var playing = (ms && ms.playbackState === 'playing') || (media ? !media.paused : false);
                  var key = t + '|' + a + '|' + al + '|' + playing;
                  if (key === last) return;
                  last = key;
                  MotorGuard.nowPlaying(t, a, al, !!playing);
                } catch (e) { /* a page mid-navigation is not an error worth reporting */ }
              }, 1000);
            })();
        """

        private val TOGGLE_PLAYBACK_JS = """
            (function () {
              var v = document.querySelector('video, audio');
              if (v) { if (v.paused) v.play(); else v.pause(); }
            })();
        """

        // YouTube Music's player bar exposes stable ids on its transport buttons; the
        // aria-label fallbacks are for if that markup ever changes under us.
        private val NEXT_TRACK_JS = """
            (function () {
              var b = document.querySelector('#next-button') ||
                document.querySelector('[aria-label="Next"]') ||
                document.querySelector('[aria-label="Next song"]');
              if (b) b.click();
            })();
        """

        private val PREVIOUS_TRACK_JS = """
            (function () {
              var b = document.querySelector('#previous-button') ||
                document.querySelector('[aria-label="Previous"]') ||
                document.querySelector('[aria-label="Previous song"]');
              if (b) b.click();
            })();
        """

        /** The mobile site: laid out for a touchscreen and far lighter than the desktop one. */
        val YouTube = WebSession(
            "https://m.youtube.com/",
            sourceId = com.motorguard.ivi.data.media.MediaSourceId.VIDEO,
        )

        /**
         * YouTube Music's web player — replaces Spotify and Anghami, both confirmed live to
         * refuse free on-demand playback in this WebView: Spotify redirects straight to its
         * Premium subscription upsell, Anghami's "Play for free" redirects to the Play Store to
         * push its native app. Plain YouTube ([YouTube], same underlying site family) was
         * confirmed live to play on demand with no account and no redirect; this is that site at
         * its music-focused address, for a proper now-playing bar and search built for songs.
         */
        val YouTubeMusic = WebSession(
            "https://music.youtube.com/",
            sourceId = com.motorguard.ivi.data.media.MediaSourceId.YOUTUBE_MUSIC,
            // Music keeps playing when the driver navigates away, like every other audio source
            // — unlike [YouTube] above, which is video and should stop when the screen is left.
            pauseOnLeave = false,
        )
    }
}
