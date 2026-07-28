// DiagnosticsFragment — owner C
// Bound to REAL vehicle hardware over CAN → VHAL.
package com.motorguard.ivi.ui.diagnostics

import androidx.fragment.app.Fragment

/**
 * FEATURES
 *  - Interactive car view with tappable hotspots
 *      (battery, tires x4, motor, brakes, doors)
 *  - Tap a component  -> zoom-in animation + live state card
 *  - Per-part telemetry: tire PSI, cell temp, charge %, health %
 *  - Overall health score ring + prioritized alerts list
 *  - Semantic severity coding (green / amber / red)
 *  - Live updates as hardware values change (property callbacks)
 * READS   : TIRE_PRESSURE, EV_BATTERY_LEVEL, cell temp, brake wear,
 *           door/lock state — all via CarDataRepository (CarPropertyManager)
 * WRITES  : none (diagnostic/read-only); can dismiss/ack alerts
 */
class DiagnosticsFragment : Fragment()
