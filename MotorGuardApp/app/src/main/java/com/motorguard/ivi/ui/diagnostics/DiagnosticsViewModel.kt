package com.motorguard.ivi.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.vehicle.api.BatteryTelemetry
import com.motorguard.ivi.data.vehicle.api.BrakeTelemetry
import com.motorguard.ivi.data.vehicle.api.Door
import com.motorguard.ivi.data.vehicle.api.DoorsTelemetry
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.data.vehicle.api.TireTelemetry
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.VehicleSeverityFlow
import com.motorguard.ivi.data.vehicle.api.isOfflineOrLoading
import com.motorguard.ivi.data.vehicle.api.latestValueOrNull
import com.motorguard.ivi.data.vehicle.fake.FakeVehicleDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the diagnostics surface (owner C — docs/05-diagnostics.md).
 *
 * - Enforces single-focus in the ViewModel (per spec), so two rapid taps can never
 *   leave two hotspots "focused."
 * - Builds the alert list from live severity, excluding dismissed entries and
 *   offline (severity == null) ones; a dismissed hotspot re-surfaces when it
 *   escalates (the dismiss map keys hotspot, not row identity, so escalating the
 *   same hotspot re-appears by construction).
 * - Auto-return: if focused with no interaction for [AUTO_RETURN_MS], snap back.
 */
class DiagnosticsViewModel(
    val source: VehicleDataSource,
    val renderer: TopDownCarRenderer,
    val severityFlow: VehicleSeverityFlow,
) : ViewModel() {

    companion object {
        const val AUTO_RETURN_MS = 9_000L
        const val FLY_TO_MS = 250
        const val FADE_OTHERS_MS = 175

        /** Manual-DI factory (no Hilt in Phase 1). */
        fun factory(source: VehicleDataSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiagnosticsViewModel(
                        source,
                        TopDownCarRenderer(),
                        VehicleSeverityFlow(source),
                    ) as T
            }
    }

    /** Currently focused hotspot; null = idle/full-car view. */
    private val _focused = MutableStateFlow<Hotspot?>(null)
    val focused: StateFlow<Hotspot?> = _focused

    private val _dismissed = MutableStateFlow<Set<Hotspot>>(emptySet())
    val dismissed: StateFlow<Set<Hotspot>> = _dismissed

    private var autoReturnJob: Job? = null

    /** Debug panel visibility. Dev-only surface; hidden by default. */
    val showDebugPanel = MutableStateFlow(false)

    val severities: StateFlow<Map<Hotspot, Severity?>> =
        severityFlow.severities.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap(),
        )

    val healthScore: StateFlow<Int?> =
        severityFlow.healthScore.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), null,
        )

    // live telemetry
    val battery: StateFlow<SignalState<BatteryTelemetry>> = source.battery
    val motor: StateFlow<SignalState<MotorTelemetry>> = source.motor
    val brakes: StateFlow<SignalState<BrakeTelemetry>> = source.brakes
    val tires: StateFlow<List<SignalState<TireTelemetry>>> = source.tires
    val doors: StateFlow<SignalState<DoorsTelemetry>> = source.doors

    data class AlertRow(
        val hotspot: Hotspot,
        val severity: Severity,
        val title: String,
        val detail: String,
    )

    /** Aggregated, worst-first alert list for the right column. */
    val alerts: StateFlow<List<AlertRow>> = combine(
        severities, dismissed, battery, motor, brakes, tires, doors,
    ) { args ->
        buildAlerts(
            severities = @Suppress("UNCHECKED_CAST") (args[0] as Map<Hotspot, Severity?>),
            dismissed = @Suppress("UNCHECKED_CAST") (args[1] as Set<Hotspot>),
            battery = @Suppress("UNCHECKED_CAST") (args[2] as SignalState<BatteryTelemetry>),
            motor = @Suppress("UNCHECKED_CAST") (args[3] as SignalState<MotorTelemetry>),
            brakes = @Suppress("UNCHECKED_CAST") (args[4] as SignalState<BrakeTelemetry>),
            tires = @Suppress("UNCHECKED_CAST") (args[5] as List<SignalState<TireTelemetry>>),
            doors = @Suppress("UNCHECKED_CAST") (args[6] as SignalState<DoorsTelemetry>),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Programmatic focus (also used by alert-row taps). */
    fun focus(hotspot: Hotspot) {
        _focused.value = hotspot
        scheduleAutoReturn()
    }

    fun requestFocus(hotspot: Hotspot) = focus(hotspot)

    fun clearFocus() {
        _focused.value = null
        autoReturnJob?.cancel()
    }

    fun noteInteraction() = scheduleAutoReturn()

    fun dismiss(hotspot: Hotspot) {
        _dismissed.value = _dismissed.value + hotspot
        noteInteraction()
    }

    fun toggleDoor(door: Door) {
        (source as? FakeVehicleDataSource)?.let { fake ->
            val current = fake.doors.value.latestValueOrNull
                ?.doors?.firstOrNull { it.door == door } ?: return
            fake.setDoor(door, open = !current.open)
        }
        noteInteraction()
    }

    fun reconnect() {
        source.reconnect()
        _dismissed.value = emptySet()
        noteInteraction()
    }

    private fun scheduleAutoReturn() {
        autoReturnJob?.cancel()
        if (_focused.value == null) return
        autoReturnJob = viewModelScope.launch {
            delay(AUTO_RETURN_MS)
            _focused.value = null
        }
    }

    private fun buildAlerts(
        severities: Map<Hotspot, Severity?>,
        dismissed: Set<Hotspot>,
        battery: SignalState<BatteryTelemetry>,
        motor: SignalState<MotorTelemetry>,
        brakes: SignalState<BrakeTelemetry>,
        tires: List<SignalState<TireTelemetry>>,
        doors: SignalState<DoorsTelemetry>,
    ): List<AlertRow> {
        fun addIf(host: Hotspot, title: String, detail: String, into: MutableList<AlertRow>) {
            val sev = severities[host] ?: return          // null = offline/loading -> never fabricate
            if (sev == Severity.OK) return
            if (host in dismissed) return
            into += AlertRow(host, severity = sev, title = title, detail = detail)
        }

        val rows = mutableListOf<AlertRow>()

        battery.latestValueOrNull?.let {
            addIf(Hotspot.BATTERY, "HV battery",
                "Charge ${it.chargePercent.toInt()}% · cell ${it.cellTempC.toInt()}°C · health ${it.healthPercent.toInt()}%", rows)
        }
        motor.latestValueOrNull?.let {
            addIf(Hotspot.MOTOR, "Motor",
                "Load ${it.loadPercent.toInt()}% · temp ${it.tempC.toInt()}°C", rows)
        }
        brakes.latestValueOrNull?.let {
            val fluid = if (it.fluidOk) "fluid OK" else "fluid LOW"
            addIf(Hotspot.BRAKES, "Brakes",
                "Pad wear ${it.padWearPercent.toInt()}% · $fluid", rows)
        }
        doors.latestValueOrNull?.let { d ->
            val open = d.doors.filter { it.open }.map { it.door.label.lowercase() }
            val unlocked = d.doors.filter { !it.locked }.map { it.door.label.lowercase() }
            val what = when {
                open.isNotEmpty() -> open.joinToString(", ", postfix = " open")
                unlocked.isNotEmpty() -> unlocked.joinToString(", ", postfix = " unlocked")
                else -> "status changed"
            }
            addIf(Hotspot.DOORS, "Doors", what, rows)
        }
        tires.forEachIndexed { i, sig ->
            val corner = Hotspot.tireCorners.getOrNull(i) ?: return@forEachIndexed
            val t = sig.latestValueOrNull ?: return@forEachIndexed
            addIf(corner, corner.label, "${t.psi.toInt()} PSI · ${t.tempC.toInt()}°C", rows)
        }

        return rows.sortedWith(compareBy({ -it.severity.ordinal }, { it.title }))
    }
}

/** Severity → user-visible color decision; null means "no live data, show grey." */
fun severityOf(
    map: Map<Hotspot, Severity?>,
    hotspot: Hotspot,
    signal: SignalState<*>,
): Severity? = if (signal.isOfflineOrLoading) null else map[hotspot]
