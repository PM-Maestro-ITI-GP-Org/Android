package com.motorguard.ivi.ui.diagnostics

import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity

/**
 * One row in the alert list: a component currently at CAUTION or CRITICAL.
 *
 * [since] is when this hotspot's severity last *changed*, not when it was first read — it drives
 * the "most recently changed first" half of the ordering and the age shown on the row.
 */
internal data class VehicleAlert(
    val hotspot: Hotspot,
    val severity: Severity,
    val since: Long,
)

/**
 * Running view of severity plus when each hotspot last changed. Accumulated with `scan` rather
 * than by mutating a map from inside a `map` operator, so the "when did this change" answer is a
 * pure function of the emission history and cannot be corrupted by a re-collection.
 */
internal data class AlertTracking(
    val severities: Map<Hotspot, Severity?> = emptyMap(),
    val changedAt: Map<Hotspot, Long> = emptyMap(),
) {
    fun advance(next: Map<Hotspot, Severity?>, nowMs: Long): AlertTracking {
        val changed = changedAt.toMutableMap()
        next.forEach { (hotspot, severity) ->
            if (severities[hotspot] != severity) changed[hotspot] = nowMs
        }
        return AlertTracking(next, changed)
    }

    /**
     * Rows to display, given which hotspots have been dismissed and at what severity.
     *
     * A dismissal is recorded *at a severity*, which is what makes escalation re-surface a row the
     * driver already waved away: a hotspot dismissed at CAUTION reappears the moment it becomes
     * CRITICAL, because the recorded severity no longer covers the current one.
     *
     * Sorted critical-first, then most-recently-changed — a new problem should never be buried
     * under an older one of the same severity.
     */
    fun visibleAlerts(dismissed: Map<Hotspot, Severity>): List<VehicleAlert> =
        severities.mapNotNull { (hotspot, severity) ->
            if (severity == null || severity == Severity.OK) return@mapNotNull null
            val dismissedAt = dismissed[hotspot]
            if (dismissedAt != null && severity <= dismissedAt) return@mapNotNull null
            VehicleAlert(hotspot, severity, changedAt[hotspot] ?: 0L)
        }.sortedWith(
            compareByDescending<VehicleAlert> { it.severity.ordinal }.thenByDescending { it.since },
        )
}
