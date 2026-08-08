package com.motorguard.ivi.ui.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motorguard.ivi.ui.theme.MotorGuardTheme
import kotlinx.coroutines.launch

/**
 * Owner C. Interactive car: tap a component -> zoom + live state card. Bound to real
 * VHAL data later. Hosts [DiagnosticsScreen]. See docs/05-diagnostics.md.
 *
 * Back is handled here rather than with Compose's `BackHandler`: `androidx.activity:
 * activity-compose` is only on this module's RUNTIME classpath (pulled in transitively by
 * SceneView), never the compile classpath, so `BackHandler` does not resolve — and adding the
 * dependency would mean editing a shared build file.
 */
class DiagnosticsFragment : Fragment() {

    /**
     * The same instance `viewModel()` resolves inside [DiagnosticsScreen]: both go through
     * `ViewModelProvider` on this fragment's store under the default key. The fragment wants it
     * only to mirror [DiagnosticsViewModel.backConsumed] onto [backCallback].
     */
    private val viewModel: DiagnosticsViewModel by viewModels()

    /**
     * Spec §7: while a hotspot is focused (or the debug drawer is open) back must clear that and
     * must NOT leave the tab.
     *
     * Starts DISABLED, and that matters: `MainActivity` registers no back callback of its own, so
     * a permanently-enabled callback here would silently swallow the press that should finish the
     * Activity, and the app would appear unable to exit from the Diagnostics tab.
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.onBackPressed()
    }

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // viewLifecycleOwner, not `this`: MainActivity.show() uses replace(), so the VIEW
        // lifecycle is what actually ends on a tab switch, and it is what must unregister this.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.backConsumed.collect { backCallback.isEnabled = it }
            }
        }
    }
}
