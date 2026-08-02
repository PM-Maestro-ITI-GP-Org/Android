package com.motorguard.ivi

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.motorguard.ivi.ui.components.NavRail
import com.motorguard.ivi.ui.components.StatusBar
import com.motorguard.ivi.ui.diagnostics.DiagnosticsFragment
import com.motorguard.ivi.ui.home.HomeFragment
import com.motorguard.ivi.ui.media.MediaFragment
import com.motorguard.ivi.ui.nav.NavFragment
import com.motorguard.ivi.ui.settings.SettingsFragment
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Single host. A fixed NavRail (Compose) on the left, one FragmentContainerView on the
 * right; tapping a rail item swaps the fragment. This is the shell every owner extends.
 *
 * Voice is intentionally NOT here — it is a system overlay (VoiceOverlayService), added
 * later. See docs/00-skeleton.md and docs/07-voice.md.
 */
class MainActivity : AppCompatActivity() {

    enum class Tab { HOME, MEDIA, NAV, DIAGNOSTICS, SETTINGS }

    private var selected by mutableStateOf(Tab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onVoice = {
                        // TODO: launch VoiceOverlayService (Hey Motor Guard). Not a tab.
                        Toast.makeText(this, "Voice assistant coming soon", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        if (savedInstanceState == null) show(Tab.HOME)
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
