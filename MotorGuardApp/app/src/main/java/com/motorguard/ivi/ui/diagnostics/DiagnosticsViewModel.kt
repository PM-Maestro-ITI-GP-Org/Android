package com.motorguard.ivi.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.VehicleSeverityFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the Diagnostics screen's state: severities derived from [VehicleData.source], plus the
 * two bits of pure UI state (which hotspot is focused, whether the debug drawer is open) that
 * have no telemetry backing at all.
 *
 * Fragment-scoped and dies on tab switch — intended. [VehicleData] is what outlives the
 * fragment; this VM is disposable scaffolding on top of it.
 */
class DiagnosticsViewModel(
    source: VehicleDataSource = VehicleData.source,
    val debugControls: VehicleDebugControls = VehicleData.debugControls,
) : ViewModel() {
    private val severityFlow = VehicleSeverityFlow(source)
    private val focused = MutableStateFlow<Hotspot?>(null)
    private val debugVisible = MutableStateFlow(false)

    val uiState: StateFlow<DiagnosticsUiState> =
        combine(severityFlow.severities, focused, debugVisible, ::DiagnosticsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    /**
     * Whether a back press should be CONSUMED by this screen rather than leaving the tab. Derived
     * here rather than in the fragment so the rule lives beside the state that decides it;
     * [DiagnosticsFragment] only mirrors this onto its `OnBackPressedCallback`.
     */
    val backConsumed: StateFlow<Boolean> =
        uiState.map { it.focusedHotspot != null || it.debugPanelVisible }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var idleJob: Job? = null

    /**
     * Spec §7's auto-return. Lives here rather than in the renderer or the screen because it
     * mutates [focused] — the single source of truth for focus — and must survive both
     * recomposition and any running camera animation.
     *
     * Restarted, not merely started, on every interaction: the timeout means "since the user last
     * touched anything", not "since focus changed". Scoped to [viewModelScope], so it dies with
     * the VM on a tab switch with no `onCleared` override needed.
     */
    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = if (focused.value == null) {
            null
        } else {
            viewModelScope.launch {
                delay(AUTO_RETURN_MILLIS)
                focused.value = null
            }
        }
    }

    /**
     * Single focus enforced HERE, not in the UI (spec §7): one nullable field, so two
     * simultaneous taps cannot produce two focused components. Tapping the focused hotspot again
     * clears focus, matching a toggle rather than a one-way drill-in.
     */
    fun onHotspotTap(hotspot: Hotspot) {
        focused.value = if (focused.value == hotspot) null else hotspot
        restartIdleTimer()
    }

    fun onBackgroundTap() {
        focused.value = null
        restartIdleTimer()
    }

    fun onStageLongPress() {
        debugVisible.value = true
        restartIdleTimer()
    }

    fun onDebugPanelDismiss() {
        debugVisible.value = false
        restartIdleTimer()
    }

    /** Any pointer-down anywhere on the screen. Deliberately cheap: a no-op when nothing is
     *  focused, which is the common case. */
    fun onUserInteraction() {
        if (focused.value == null) return
        restartIdleTimer()
    }

    /**
     * Back priority: close the debug drawer first, then clear focus. Only ever invoked while
     * [backConsumed] is true, so it always has something to do.
     */
    fun onBackPressed() {
        if (debugVisible.value) debugVisible.value = false else focused.value = null
        restartIdleTimer()
    }
}

/** Spec §7 asks for 8-10 s; the midpoint reads as unhurried without feeling stuck. */
private const val AUTO_RETURN_MILLIS = 9_000L
