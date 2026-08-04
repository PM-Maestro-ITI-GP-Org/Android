package com.motorguard.ivi.ui.diagnostics

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
 * Owner C. Interactive car: tap a component -> zoom + live state card. Bound to real
 * VHAL data later. Skeleton stage: just a placeholder. See docs/05-diagnostics.md.
 */
class DiagnosticsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { Placeholder("Diagnostics") }
        }
    }
}
