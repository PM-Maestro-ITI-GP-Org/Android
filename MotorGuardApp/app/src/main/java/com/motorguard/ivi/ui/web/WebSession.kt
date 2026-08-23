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
    /** Overrides the WebView's own UA where a site refuses it. See [Spotify]. */
    private val userAgent: String? = null,
    /**
     * Whether leaving the tab should stop the page.
     *
     * True for video, where walking away from a film should stop it. False for music: Spotify is
     * a media source like any other, and a source that stopped the moment the driver looked at
     * the map would be useless — it also has to keep playing for the now-playing card to have
     * anything to show.
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
     * [com.motorguard.ivi.data.media.PlaybackKind.WEB]), but a plain HTML5 `<video>` element is a
     * standard, stable target to toggle from outside — unlike guessing at a site's own button
     * markup, which breaks the moment its DOM changes. YouTube's mobile site plays through one;
     * Spotify's Web Playback SDK does not expose one, so this is a harmless no-op there and its
     * own page remains the only way to control it, same as before.
     */
    fun togglePlayback() {
        webView?.evaluateJavascript(TOGGLE_PLAYBACK_JS, null)
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
                  // <audio> as well as <video>: Spotify's web player has no video element at
                  // all, it plays through <audio> (or, where that is blocked, an inaudible one
                  // it still creates) -- checking video only meant Spotify could never be
                  // detected as playing even when it genuinely was, because the metadata API
                  // isn't reliably populated by every site in an embedded WebView.
                  var media = document.querySelector('video, audio');
                  // Fall back to the media element and the document title: not every site
                  // populates the Media Session API, but there is always an element and a
                  // title once something is playing.
                  var t = (m && m.title) || (media && !media.paused ? document.title.replace(/ - YouTube$/, '') : '');
                  var a = (m && m.artist) || '';
                  var al = (m && m.album) || '';
                  var playing = (ms && ms.playbackState === 'playing') || (media ? !media.paused : false);
                  // Spotify's web player calls neither of the above in this embedded WebView --
                  // confirmed live: its own now-playing bar shows a real track, playing audibly,
                  // while navigator.mediaSession stays unset and no <audio>/<video> element ever
                  // appears (it renders through Web Audio, not a plain media element). Read its
                  // own now-playing bar directly when nothing else found anything -- these
                  // data-testid values are what Spotify's web client itself uses to label the
                  // bar, and have stayed stable across web-client releases.
                  if (!t) {
                    var stTitle = document.querySelector('[data-testid="context-item-info-title"]');
                    if (stTitle) {
                      t = stTitle.textContent || '';
                      var stArtist = document.querySelector('[data-testid="context-item-info-subtitles"]');
                      a = stArtist ? (stArtist.textContent || '') : '';
                      var stPlayBtn = document.querySelector('[data-testid="control-button-playpause"]');
                      var stLabel = stPlayBtn ? (stPlayBtn.getAttribute('aria-label') || '') : '';
                      playing = stLabel.toLowerCase().indexOf('pause') !== -1;
                    }
                  }
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
              var v = document.getElementsByTagName('video')[0];
              if (v) { if (v.paused) v.play(); else v.pause(); }
            })();
        """

        /** The mobile site: laid out for a touchscreen and far lighter than the desktop one. */
        val YouTube = WebSession(
            "https://m.youtube.com/",
            sourceId = com.motorguard.ivi.data.media.MediaSourceId.VIDEO,
        )

        /**
         * Spotify's web player.
         *
         * Note this board has no Widevine (`dumpsys media.drm` is empty), so protected playback
         * may refuse. Browsing, search and the account all work regardless, and the moment DRM
         * is added to the image this needs no change.
         */
        val Spotify = WebSession(
            "https://open.spotify.com/",
            sourceId = com.motorguard.ivi.data.media.MediaSourceId.SPOTIFY,
            // Spotify refuses the stock WebView agent outright — it renders "Unsupported
            // browser" and nothing else, because the string carries the "; wv" marker that
            // identifies an embedded view. The engine underneath *is* Chrome 128, so claiming
            // Chrome is accurate about capability rather than a disguise.
            userAgent = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.6613.88 Mobile Safari/537.36",
            // Music keeps playing when the driver navigates away, like every other audio source.
            pauseOnLeave = false,
        )

        /**
         * Anghami's web player — offered alongside Spotify because Spotify's free tier gates
         * full playback behind Premium (confirmed live: tapping play redirected the whole page
         * to its subscription upsell instead of playing anything). Anghami's free tier is
         * ad-supported and does not carry that wall.
         */
        val Anghami = WebSession(
            "https://play.anghami.com/",
            sourceId = com.motorguard.ivi.data.media.MediaSourceId.ANGHAMI,
            // Same reasoning as Spotify's override: claim the real underlying engine rather
            // than the "; wv" embedded-WebView marker some sites refuse outright.
            userAgent = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.6613.88 Mobile Safari/537.36",
            pauseOnLeave = false,
        )
    }
}
