package com.motorguard.ivi.ui.nav

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
 * Navigation tab. Deferred per the design (full turn-by-turn is future work); shown in
 * the rail as a placeholder for now. See README 4.3.
 */
class NavFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { Placeholder("Navigation") }
        }
    }
}
