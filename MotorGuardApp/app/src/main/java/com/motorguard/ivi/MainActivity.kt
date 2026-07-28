package com.motorguard.ivi

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.motorguard.ivi.ui.components.NavRail
import com.motorguard.ivi.ui.diagnostics.DiagnosticsFragment
import com.motorguard.ivi.ui.home.HomeFragment
import com.motorguard.ivi.ui.media.MediaFragment
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

    enum class Tab { HOME, MEDIA, DIAGNOSTICS, SETTINGS }

    private var selected by mutableStateOf(Tab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ComposeView>(R.id.nav_rail).setContent {
            MotorGuardTheme {
                NavRail(selected = selected, onSelect = ::show)
            }
        }

        if (savedInstanceState == null) show(Tab.HOME)
    }

    private fun show(tab: Tab) {
        // Re-tapping the current tab is a no-op (never reload the fragment).
        if (tab == selected && supportFragmentManager.findFragmentById(R.id.fragment_container) != null) return
        selected = tab

        val fragment: Fragment = when (tab) {
            Tab.HOME -> HomeFragment()
            Tab.MEDIA -> MediaFragment()
            Tab.DIAGNOSTICS -> DiagnosticsFragment()
            Tab.SETTINGS -> SettingsFragment()
        }
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment)
        }
    }
}
