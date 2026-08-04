package com.motorguard.ivi.ui.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Owner B. Multi-source playback (Library / USB / Bluetooth / Radio) with a shared transport bar.
 *
 * The fragment is only a window onto the player — playback itself lives in
 * [com.motorguard.ivi.media.MotorGuardMediaService] and keeps running when this tab is gone. See
 * docs/04-media.md.
 */
class MediaFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { MediaScreen() }
        }
    }
}
