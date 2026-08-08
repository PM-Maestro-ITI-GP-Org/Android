package com.motorguard.ivi.ui.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Owner C. Interactive car: tap a component -> zoom + live state card. Bound to real
 * VHAL data later. Hosts [DiagnosticsScreen]. Back handling arrives in Step 3 via
 * requireActivity().onBackPressedDispatcher. See docs/05-diagnostics.md.
 */
class DiagnosticsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        // DisposeOnViewTreeLifecycleDestroyed is what makes the Filament teardown correct:
        // MainActivity.show() -> replace() destroys this fragment's view -> destroys this
        // composition -> runs every remember* helper's onDispose -> destroys nodes, loaders,
        // then the Engine. Do not change this strategy.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { DiagnosticsScreen() }
        }
    }
}
