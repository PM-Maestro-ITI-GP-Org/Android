package com.motorguard.ivi.ui.media

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
 * Owner B. Multi-source playback (USB / Bluetooth / Radio) + shared transport bar.
 * Skeleton stage: just a placeholder. See docs/04-media.md.
 */
class MediaFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { Placeholder("Media") }
        }
    }
}
