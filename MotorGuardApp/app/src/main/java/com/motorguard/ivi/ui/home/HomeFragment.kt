package com.motorguard.ivi.ui.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.motorguard.ivi.MainActivity
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Owner A. Glanceable hub: battery/range rings, mini-map, weather + now-playing widgets.
 *
 * The now-playing widget is live — it observes the media service directly, so it keeps updating
 * while music plays on any tab. The remaining cards are named placeholders for owner A. See
 * docs/03-home.md.
 */
class HomeFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme {
                HomeScreen(
                    onOpenMedia = { (activity as? MainActivity)?.openTab(MainActivity.Tab.MEDIA) },
                )
            }
        }
    }
}
