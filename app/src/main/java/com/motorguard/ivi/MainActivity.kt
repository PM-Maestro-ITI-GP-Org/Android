package com.motorguard.ivi

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.data.LocalStore
import com.motorguard.ivi.ui.theme.ThemeState
import com.motorguard.ivi.data.media.AlbumArtLoader
import com.motorguard.ivi.media.MediaConnection
import com.motorguard.ivi.ui.theme.AlbumThemeState
import com.motorguard.ivi.ui.theme.extractAlbumSeed
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.motorguard.ivi.ui.components.NavRail
import com.motorguard.ivi.ui.components.StatusBar
import com.motorguard.ivi.ui.diagnostics.DiagnosticsFragment
import com.motorguard.ivi.ui.home.HomeFragment
import com.motorguard.ivi.ui.media.MediaFragment
import com.motorguard.ivi.ui.nav.NavFragment
import com.motorguard.ivi.ui.settings.SettingsFragment
import com.motorguard.ivi.ui.theme.MotorGuardTheme
import com.motorguard.ivi.ui.voice.VoiceTrigger

/**
 * Single host. A fixed NavRail (Compose) on the left, one FragmentContainerView on the
 * right; tapping a rail item swaps the fragment. This is the shell every owner extends.
 *
 * Voice is intentionally NOT a tab — it is a system overlay (VoiceOverlayService) that
 * floats over whatever is showing. The rail mic button only *asks* for a session;
 * the wake word is the primary trigger. See docs/00-skeleton.md and docs/07-voice.md.
 */
class MainActivity : AppCompatActivity() {

    enum class Tab { HOME, MEDIA, NAV, DIAGNOSTICS, SETTINGS }

    companion object {
        /** Voice overlay routes here: putExtra(EXTRA_TAB, Tab.MEDIA.name). */
        const val EXTRA_TAB = "com.motorguard.ivi.EXTRA_TAB"

        /** Big enough for Palette to find a stable dominant colour, small enough to be cheap. */
        private const val ARTWORK_PX = 256
    }

    private var selected by mutableStateOf(Tab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before Conn/ThemeState are touched: both restore their saved state from it.
        LocalStore.init(this)
        ThemeState.restore()
        Conn.init(this)
        maybeRequestConnectivityPermissions()
        setContentView(R.layout.activity_main)
        enableImmersiveMode()

        findViewById<ComposeView>(R.id.status_bar).setContent {
            MotorGuardTheme { StatusBar() }
        }

        findViewById<ComposeView>(R.id.nav_rail).setContent {
            MotorGuardTheme {
                NavRail(
                    selected = selected,
                    onSelect = ::show,
                    onVoice = { VoiceTrigger.show(this) },
                )
            }
        }

        if (savedInstanceState == null) show(tabFromIntent(intent) ?: Tab.HOME)

        followAlbumArtwork()
    }

    /**
     * Keep the app-wide accent in step with the playing track.
     *
     * Driven from the single Activity rather than from a composable, because the accent applies
     * to every surface — including ones that are not composed at the time the track changes. One
     * observer, one artwork load, one palette extraction; [MotorGuardTheme] reads the result.
     */
    private fun followAlbumArtwork() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaConnection.get(this@MainActivity).state
                    .map { it.track }
                    .distinctUntilChangedBy { it?.id }
                    .collect { track ->
                        val artwork = AlbumArtLoader.load(this@MainActivity, track, ARTWORK_PX)
                        AlbumThemeState.setArtwork(artwork?.let { extractAlbumSeed(it) })
                    }
            }
        }
    }

    /** The voice overlay brings a tab forward by re-launching us with EXTRA_TAB. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFromIntent(intent)?.let(::show)
    }

    private fun tabFromIntent(intent: Intent?): Tab? =
        intent?.getStringExtra(EXTRA_TAB)?.let { name ->
            runCatching { Tab.valueOf(name) }.getOrNull()
        }

    /**
     * Take over the full screen: draw edge-to-edge and hide the Automotive system bars
     * (top status bar + bottom nav/climate bar). The bars stay swipe-revealable.
     *
     * NOTE: on stock AAOS the CarSystemBars are "persistent" and ignore this request.
     * The device must have the immersive system-bar policy enabled:
     *   adb shell cmd overlay enable com.android.car.systemui.systembar.persistency.immersive
     * On a real build, bake that overlay (or config_enable*SystemBar=false) into the image.
     */
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Re-assert immersive mode whenever this window becomes the focused one again.
     *
     * Hiding the bars is a property of the *focused* window, so anything that takes focus away —
     * a permission dialog, the Bluetooth or Wi-Fi panel Settings opens, the notification shade —
     * hands it back with the bars showing, and nothing puts them away again. That is the "the
     * status bar appears and then stays" case: the transient-reveal timeout never applies,
     * because from the framework's point of view the bars were never transient.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        // Covers returning from another activity without a focus change of our own.
        enableImmersiveMode()
    }

    /**
     * Request the runtime (dangerous) permissions the real Wi-Fi/BT path needs. Only on
     * the real build (bool/use_real_connectivity); the emulator uses mock data and skips
     * the prompts. Privileged/signature perms come from the privapp allow-list, not here.
     */
    private fun maybeRequestConnectivityPermissions() {
        if (!resources.getBoolean(R.bool.use_real_connectivity)) return
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
        ActivityCompat.requestPermissions(this, perms, 1001)
    }

    /**
     * Switch tabs from inside a fragment — the Home now-playing card opening Media, per
     * docs/03-home.md. Fragments reach this with `(activity as? MainActivity)?.openTab(...)`.
     */
    fun openTab(tab: Tab) = show(tab)

    private fun show(tab: Tab) {
        // Re-tapping the current tab is a no-op (never reload the fragment).
        if (tab == selected && supportFragmentManager.findFragmentById(R.id.fragment_container) != null) return
        selected = tab

        val fragment: Fragment = when (tab) {
            Tab.HOME -> HomeFragment()
            Tab.MEDIA -> MediaFragment()
            Tab.NAV -> NavFragment()
            Tab.DIAGNOSTICS -> DiagnosticsFragment()
            Tab.SETTINGS -> SettingsFragment()
        }
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment)
        }
    }
}
