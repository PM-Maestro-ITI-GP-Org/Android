package com.motorguard.ivi.ui.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Navigation tab: search a destination, preview the routes, then turn-by-turn guidance.
 *
 * Map, routing and search are all open source and provider-swappable — knobs live in
 * [com.motorguard.ivi.data.nav.NavConfig], rationale in docs/08-navigation.md. Nothing here
 * depends on Google Play Services, which this AOSP image does not ship.
 */
class NavFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { NavScreen() }
        }
    }
}
