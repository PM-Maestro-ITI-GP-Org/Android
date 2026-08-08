package com.motorguard.ivi.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.vehicle.api.BatteryTelemetry
import com.motorguard.ivi.data.vehicle.api.BrakeTelemetry
import com.motorguard.ivi.data.vehicle.api.DoorsTelemetry
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.SeverityResolver
import com.motorguard.ivi.data.vehicle.api.TireTelemetry
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.VehicleSeverityFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val source: VehicleDataSource = VehicleData.source,
    val debugControls: VehicleDebugControls = VehicleData.debugControls,
) : ViewModel() {

    /**
     * ONE resolver, shared between the per-hotspot severity map that colours the dots and the
     * per-field grading that colours the detail card. Two instances would each carry their own
     * hysteresis state, so near a threshold the card could disagree with the dot it belongs to.
     *
     * Sharing is safe because both pipelines run on [viewModelScope] against a main-thread-confined
     * source — single-threaded, which is exactly the contract [SeverityResolver] documents — and
     * because resolving the same value twice is idempotent, so the card re-grading the current
     * sample cannot perturb the hysteresis the dots depend on.
     */
    private val resolver = SeverityResolver()

    private val severityFlow = VehicleSeverityFlow(source, resolver)
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

    /**
     * Telemetry for whichever hotspot is focused, already graded, or null when nothing is focused.
     *
     * Deliberately a second [StateFlow] rather than a field of [DiagnosticsUiState]: telemetry
     * ticks continuously, and folding it into the ui state would invalidate the hotspot overlay
     * and the car stage — and therefore the Filament surface — several times a second for data
     * neither of them reads.
     *
     * `flatMapLatest` rather than a six-way `combine`: only the focused signal is collected, and
     * nothing at all is collected while unfocused, which is the common case.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val focusedTelemetry: StateFlow<FocusedTelemetry?> =
        focused
            .flatMapLatest { hotspot -> telemetryFlowFor(hotspot) }
            // The source republishes every flow several times a second with an unchanged payload
            // and a fresh timestamp. Without this the card would recompose ~5 Hz for numbers that
            // never moved.
            .distinctUntilChanged { a, b -> a.rendersSameAs(b) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun telemetryFlowFor(hotspot: Hotspot?): Flow<FocusedTelemetry?> = when (hotspot) {
        null -> flowOf(null)
        Hotspot.BATTERY -> source.battery.map { FocusedTelemetry.Battery(it.mapData(::gradeBattery)) }
        Hotspot.MOTOR -> source.motor.map { FocusedTelemetry.Motor(it.mapData(::gradeMotor)) }
        Hotspot.BRAKES -> source.brakes.map { FocusedTelemetry.Brakes(it.mapData(::gradeBrakes)) }
        Hotspot.DOORS -> source.doors.map { FocusedTelemetry.Doors(it.mapData(::gradeDoors)) }
        Hotspot.TIRE_FL, Hotspot.TIRE_FR, Hotspot.TIRE_RL, Hotspot.TIRE_RR ->
            source.tires.map { list ->
                FocusedTelemetry.Tire(hotspot, list.forCorner(hotspot).mapData { gradeTire(hotspot, it) })
            }
    }

    /*
     * The entire severity surface of the detail card: five straight delegations to the shared
     * resolver, no arithmetic of their own. No threshold number appears in the UI layer.
     *
     * Motor RPM, tire temperature, battery cycle count and the charging flag are deliberately
     * ungraded — SeverityThresholds defines no rule for them, and inventing one here would put
     * severity logic outside SeverityEvaluator, which the brief forbids. They render neutral.
     *
     * These run inside a Flow.map, never inside a composable: they mutate the resolver's hysteresis
     * state, and a composition can be skipped, restarted or discarded at will.
     */
    private fun gradeBattery(t: BatteryTelemetry) = BatteryReading(
        data = t,
        charge = resolver.batteryCharge(t.chargePercent),
        cellTemp = resolver.cellTemp(t.cellTempC),
        health = resolver.batteryHealth(t.healthPercent),
    )

    private fun gradeMotor(t: MotorTelemetry) = MotorReading(
        data = t,
        load = resolver.motorLoad(t.loadPercent),
        temp = resolver.motorTemp(t.tempC),
    )

    private fun gradeBrakes(t: BrakeTelemetry) = BrakeReading(
        data = t,
        wear = resolver.brakeWear(t.padWearPercent),
        fluid = resolver.brakeFluid(t.fluidOk),
    )

    private fun gradeDoors(t: DoorsTelemetry) = DoorsReading(t, resolver.doors(t))

    private fun gradeTire(corner: Hotspot, t: TireTelemetry) =
        TireReading(t, resolver.tirePsi(corner, t.psi))

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
