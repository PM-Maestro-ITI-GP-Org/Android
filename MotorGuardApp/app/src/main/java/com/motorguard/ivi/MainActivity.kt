package com.motorguard.ivi

import android.content.Intent
import android.os.Bundle
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
import androidx.lifecycle.ViewModelProvider
import com.motorguard.ivi.data.PhoneRepository
import com.motorguard.ivi.ui.components.NavRail
import com.motorguard.ivi.ui.components.StatusBar
import com.motorguard.ivi.ui.diagnostics.DiagnosticsFragment
import com.motorguard.ivi.ui.dialer.DialerFragment
import com.motorguard.ivi.ui.dialer.DialerViewModel
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

    enum class Tab { HOME, MEDIA, NAV, DIAGNOSTICS, PHONE, SETTINGS }

    companion object {
        /** Voice overlay routes here: putExtra(EXTRA_TAB, Tab.MEDIA.name). */
        const val EXTRA_TAB = "com.motorguard.ivi.EXTRA_TAB"

        /**
         * Place a call as soon as the phone tab is up. The voice overlay resolves
         * "call Mona" to a number first (PhoneRepository.lookup) and passes both, so the
         * screen can label the call before the far end answers.
         */
        const val EXTRA_DIAL_NUMBER = "com.motorguard.ivi.EXTRA_DIAL_NUMBER"
        const val EXTRA_DIAL_NAME = "com.motorguard.ivi.EXTRA_DIAL_NAME"
    }

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
                    onVoice = { VoiceTrigger.show(this) },
                )
            }
        }

        if (savedInstanceState == null) {
            show(tabFromIntent(intent) ?: Tab.HOME)
            applyPhoneExtras(intent)
        }
    }

    /** The voice overlay brings a tab forward by re-launching us with EXTRA_TAB. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFromIntent(intent)?.let(::show)
        applyPhoneExtras(intent)
    }

    private fun tabFromIntent(intent: Intent?): Tab? {
        intent ?: return null
        intent.getStringExtra(EXTRA_TAB)?.let { name ->
            runCatching { Tab.valueOf(name) }.getOrNull()?.let { return it }
        }
        // ACTION_DIAL / tel: — arrives from the DIALER role and from other apps.
        val isTel = intent.data?.scheme == "tel"
        return if (isTel || intent.getStringExtra(EXTRA_DIAL_NUMBER) != null) Tab.PHONE else null
    }

    /**
     * A `tel:` URI prefills the pad and waits for the driver to hit call; an explicit
     * EXTRA_DIAL_NUMBER (voice) dials straight away, because the driver already said so
     * out loud and a second confirmation tap defeats the point of hands-free.
     */
    private fun applyPhoneExtras(intent: Intent?) {
        intent ?: return

        intent.getStringExtra(EXTRA_DIAL_NUMBER)?.let { number ->
            PhoneRepository.get(this).dial(number, intent.getStringExtra(EXTRA_DIAL_NAME))
            intent.removeExtra(EXTRA_DIAL_NUMBER)
            return
        }

        if (intent.data?.scheme == "tel") {
            val number = intent.data?.schemeSpecificPart.orEmpty()
            if (number.isNotEmpty()) {
                ViewModelProvider(this)[DialerViewModel::class.java].prefillDigits(number)
            }
            intent.data = null
        }
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

    private fun show(tab: Tab) {
        // Re-tapping the current tab is a no-op (never reload the fragment).
        if (tab == selected && supportFragmentManager.findFragmentById(R.id.fragment_container) != null) return
        selected = tab

        val fragment: Fragment = when (tab) {
            Tab.HOME -> HomeFragment()
            Tab.MEDIA -> MediaFragment()
            Tab.NAV -> NavFragment()
            Tab.DIAGNOSTICS -> DiagnosticsFragment()
            Tab.PHONE -> DialerFragment()
            Tab.SETTINGS -> SettingsFragment()
        }
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment)
        }
    }
}