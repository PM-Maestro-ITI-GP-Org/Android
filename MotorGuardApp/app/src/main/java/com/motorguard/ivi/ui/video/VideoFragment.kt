package com.motorguard.ivi.ui.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.MainActivity
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Videos, as a destination of its own rather than a tab inside Media.
 *
 * Playback is local to this screen's player: leaving Videos stops the film, which is the right
 * behaviour and also the only one available — a `MediaController` cannot be handed a surface, so
 * video cannot go through the shared session. See
 * [com.motorguard.ivi.data.media.PlaybackKind.VIDEO].
 */
class VideoFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme {
                VideoScreen(
                    // Full screen means full screen: the rail and status bar step aside so the
                    // picture gets the whole panel.
                    onFullscreenChange = { (activity as? MainActivity)?.setChromeVisible(!it) },
                )
            }
        }
    }
}
