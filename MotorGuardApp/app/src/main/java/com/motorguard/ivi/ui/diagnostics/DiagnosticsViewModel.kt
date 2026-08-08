package com.motorguard.ivi.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.VehicleSeverityFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
     * Single focus enforced HERE, not in the UI (spec §7): one nullable field, so two
     * simultaneous taps cannot produce two focused components. Tapping the focused hotspot again
     * clears focus, matching a toggle rather than a one-way drill-in.
     */
    fun onHotspotTap(hotspot: Hotspot) {
        focused.value = if (focused.value == hotspot) null else hotspot
    }

    fun onBackgroundTap() {
        focused.value = null
    }

    fun onStageLongPress() {
        debugVisible.value = true
    }

    fun onDebugPanelDismiss() {
        debugVisible.value = false
    }
}
