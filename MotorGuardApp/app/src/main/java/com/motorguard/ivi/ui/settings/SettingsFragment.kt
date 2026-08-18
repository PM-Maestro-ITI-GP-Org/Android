package com.motorguard.ivi.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Owner E. Wi-Fi / Bluetooth / Theme & Display / System sub-tabs are still a
 * skeleton -- see docs/06-settings.md. Voice Assistant is real: it is the one
 * setting the voice pipeline actually reads (VoicePrefs).
 */
class SettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { SettingsScreen() }
        }
    }
}
