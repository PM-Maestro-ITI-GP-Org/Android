package com.motorguard.ivi.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.ui.components.Placeholder
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Owner E. Wi-Fi / Bluetooth / Theme & Display / System sub-tabs. Skeleton stage: just
 * a placeholder. See docs/06-settings.md.
 */
class SettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { Placeholder("Settings") }
        }
    }
}
