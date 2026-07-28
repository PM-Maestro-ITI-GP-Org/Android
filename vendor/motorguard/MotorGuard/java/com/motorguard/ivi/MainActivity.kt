// vendor/motorguard/MotorGuard/java/com/motorguard/ivi/MainActivity.kt
package com.motorguard.ivi

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.motorguard.ivi.ui.home.HomeFragment
import com.motorguard.ivi.ui.media.MediaFragment
import com.motorguard.ivi.ui.diagnostics.DiagnosticsFragment
import com.motorguard.ivi.ui.settings.SettingsFragment

/**
 * Single host. NavRail on the left, one FragmentContainerView on the right.
 * Tapping a rail item swaps the fragment — the shell everyone extends.
 * Voice is NOT here: it is a system overlay (VoiceOverlayService).
 */
class MainActivity : AppCompatActivity() {

    enum class Tab { HOME, MEDIA, DIAGNOSTICS, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) show(Tab.HOME)
        // navRail.onItemSelected = { show(it) }
    }

    private fun show(tab: Tab) {
        val fragment: Fragment = when (tab) {
            Tab.HOME        -> HomeFragment()
            Tab.MEDIA       -> MediaFragment()
            Tab.DIAGNOSTICS -> DiagnosticsFragment()
            Tab.SETTINGS    -> SettingsFragment()
        }
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment)
        }
    }
}
