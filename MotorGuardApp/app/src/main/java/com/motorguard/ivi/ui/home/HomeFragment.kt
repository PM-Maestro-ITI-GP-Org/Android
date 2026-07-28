package com.motorguard.ivi.ui.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.ui.components.Placeholder
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Owner A. Glanceable hub: battery/range rings, mini-map, weather + now-playing widgets.
 * Skeleton stage: just a placeholder. See docs/03-home.md.
 */
class HomeFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { Placeholder("Home") }
        }
    }
}
