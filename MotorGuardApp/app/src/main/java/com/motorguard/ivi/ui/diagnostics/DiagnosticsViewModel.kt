package com.motorguard.ivi.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.vehicle.api.BatteryTelemetry
import com.motorguard.ivi.data.vehicle.api.BrakeTelemetry
import com.motorguard.ivi.data.vehicle.api.DoorsTelemetry
import com.motorguard.ivi.data.vehicle.api.CaptureState
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.MotorCaptureSource
import com.motorguard.ivi.data.vehicle.api.MotorCaptureSummary
import com.motorguard.ivi.data.vehicle.api.summarise
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SeverityResolver
import com.motorguard.ivi.data.vehicle.api.TireTelemetry
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.VehicleSeverityFlow
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val captureSource: MotorCaptureSource = VehicleData.captureSource,
) : ViewModel() {

    /**
     * The engineering-insights popup, or null when it is closed. Held here rather than as local
     * composable state so a capture already in flight survives the recomposition that opens the
     * panel, and so closing the popup does not silently abandon a request the diagnostics unit is
     * still working on.
     */
    private val capture = MutableStateFlow<CaptureState?>(null)
    val captureState: StateFlow<CaptureState?> = capture

    /** Only ever one request in flight: the button is disabled while [CaptureState.Requesting], but
     *  a double tap that beats the recomposition would otherwise start a second acquisition. */
    private var captureJob: Job? = null

    fun onInsightsOpen() {
        if (capture.value == null) capture.value = CaptureState.Idle
        if (capture.value !is CaptureState.Ready) requestCapture()
        restartIdleTimer()
    }

    fun onInsightsDismiss() {
        capture.value = null
        captureJob?.cancel()
        captureJob = null
        restartIdleTimer()
    }

    /**
     * The analysis of the most recent successful capture, which is where every quantitative claim
     * about the motor now comes from — the vehicle publishes only a classification.
     *
     * Survives closing the popup: having fetched a capture, the card keeps showing what it said
     * until a newer one replaces it. Cleared only by a failed or in-flight request replacing it,
     * never by dismissing the panel.
     */
    private val captureSummary = MutableStateFlow<MotorCaptureSummary?>(null)

    fun requestCapture() {
        if (captureJob?.isActive == true) return
        capture.value = CaptureState.Requesting
        captureJob = viewModelScope.launch {
            val result = captureSource.requestCapture()
            capture.value = result
            // Summarised once, here, rather than on every recomposition of the card — and OFF the
            // main thread, because it is a pass over 200,000 samples across twelve channels and
            // viewModelScope runs on Main. Doing it inline stalls the frame that is animating the
            // popup open.
            if (result is CaptureState.Ready) {
                captureSummary.value = withContext(Dispatchers.Default) { result.capture.summarise() }
            }
        }
        restartIdleTimer()
    }

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
        // The only hotspot whose card shows something the vehicle did not send: the capture
        // analysis is joined in here so the card receives one already-assembled reading.
        Hotspot.MOTOR -> combine(source.motor, captureSummary) { signal, summary ->
            FocusedTelemetry.Motor(signal.mapData { MotorReading(it, summary) })
        }
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
     * Motor RPM, battery cycle count and the charging flag remain deliberately ungraded —
     * SeverityThresholds defines no rule for them, and inventing one here would put severity logic
     * outside SeverityEvaluator, which the brief forbids. They render neutral. Tire temperature
     * used to be in that list; it now has a real rule in the domain module and is graded.
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

    private fun gradeBrakes(t: BrakeTelemetry) = BrakeReading(
        data = t,
        wear = resolver.brakeWear(t.padWearPercent),
        fluid = resolver.brakeFluid(t.fluidOk),
    )

    private fun gradeDoors(t: DoorsTelemetry) = DoorsReading(t, resolver.doors(t))

    private fun gradeTire(corner: Hotspot, t: TireTelemetry) = TireReading(
        data = t,
        psi = resolver.tirePsi(corner, t.psi),
        temp = resolver.tireTemp(corner, t.tempC),
    )

    /**
     * Hotspots the driver has dismissed, recorded WITH the severity they were dismissed at.
     *
     * Storing the severity rather than a bare flag is what makes escalation re-alert: a row waved
     * away at CAUTION comes straight back at CRITICAL. Entries are pruned the moment a hotspot
     * returns to OK (see the collector in `init`), so a component that degrades again later starts
     * from a clean slate. In-memory only; the brief puts persistence out of scope for phase 1.
     */
    private val dismissed = MutableStateFlow<Map<Hotspot, Severity>>(emptyMap())

    private val tracking: StateFlow<AlertTracking> =
        severityFlow.severities
            .scan(AlertTracking()) { acc, next -> acc.advance(next, System.currentTimeMillis()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertTracking())

    /** Every hotspot at CAUTION or CRITICAL that has not been dismissed at its current severity. */
    internal val alerts: StateFlow<List<VehicleAlert>> =
        combine(tracking, dismissed) { t, d -> t.visibleAlerts(d) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Aggregate health, or null while no signal has produced a severity yet.
     *
     * Note this is derived from the SAME severity flow the dots and cards use, and dismissal is
     * deliberately NOT an input: acknowledging an alert must never improve the score, or the ring
     * would report a healthier car than the one on screen.
     */
    val healthScore: StateFlow<Int?> =
        severityFlow.healthScore
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // A dismissal only survives while the hotspot is still alerting. Once it returns to OK the
        // record is dropped, so a later degrade raises a fresh row rather than staying silent
        // because of an acknowledgement the driver made about a different episode.
        viewModelScope.launch {
            severityFlow.severities.collect { map ->
                val kept = dismissed.value.filterKeys { hotspot ->
                    map[hotspot].let { it != null && it != Severity.OK }
                }
                if (kept != dismissed.value) dismissed.value = kept
            }
        }
    }

    /**
     * Acknowledge an alert. Removes the ROW and nothing else — the hotspot's severity, its dot
     * colour and the health ring are all untouched, because the car does not become healthier by
     * being told to be quiet.
     */
    fun onDismissAlert(hotspot: Hotspot) {
        val current = tracking.value.severities[hotspot] ?: return
        if (current == Severity.OK) return
        dismissed.value = dismissed.value + (hotspot to current)
        restartIdleTimer()
    }

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
