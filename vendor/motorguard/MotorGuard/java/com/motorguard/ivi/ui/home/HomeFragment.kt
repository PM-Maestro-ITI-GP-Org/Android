// HomeFragment — owner A
// Glanceable hub. Read-only widgets that deep-link into other tabs.
package com.motorguard.ivi.ui.home

import androidx.fragment.app.Fragment

/**
 * FEATURES
 *  - Battery % + range gauge rings          (CarDataRepository.vehicleState)
 *  - Mini-map card with ETA                 (static image / nav provider)
 *  - Weather widget + now-playing widget    (equal height, right column)
 *  - Vehicle Status / Service shortcuts     (deep-link to Diagnostics)
 *  - Three separated GlassCards: Map · Vehicle · Weather+Media
 * READS   : EV_BATTERY_LEVEL, RANGE_REMAINING, active MediaSession, weather
 * WRITES  : none (launch intents only)
 */
class HomeFragment : Fragment()
