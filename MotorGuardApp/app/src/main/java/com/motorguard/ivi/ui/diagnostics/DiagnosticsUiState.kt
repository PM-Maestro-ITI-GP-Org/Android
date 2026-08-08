package com.motorguard.ivi.ui.diagnostics

import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity

/**
 * Everything [DiagnosticsScreenContent] needs to render a frame, collapsed into one immutable
 * snapshot so the screen never has to reconcile several independent flows itself.
 *
 * @param severities exactly the 8 [Hotspot] keys once telemetry has emitted at least once; a
 *   null VALUE means "signal not live" (offline/loading -> grey dot, no pulse — spec §8). An
 *   EMPTY MAP means the screen has not received its first emission yet at all. These are
 *   different states that render differently (blank stage vs. eight grey dots), hence
 *   [isLoading] as its own flag rather than collapsing both into "no data".
 */
data class DiagnosticsUiState(
    val severities: Map<Hotspot, Severity?> = emptyMap(),
    val focusedHotspot: Hotspot? = null,
    val debugPanelVisible: Boolean = false,
) {
    val isLoading: Boolean get() = severities.isEmpty()

    fun severityOf(hotspot: Hotspot): Severity? = severities[hotspot]

    /** False while loading -> the dot for [hotspot] renders subdued grey with no pulse. */
    fun hasSignal(hotspot: Hotspot): Boolean = severities.containsKey(hotspot)
}
