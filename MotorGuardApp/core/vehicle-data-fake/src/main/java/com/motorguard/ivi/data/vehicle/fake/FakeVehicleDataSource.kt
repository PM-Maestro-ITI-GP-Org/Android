package com.motorguard.ivi.data.vehicle.fake

import com.motorguard.ivi.data.vehicle.api.BatteryTelemetry
import com.motorguard.ivi.data.vehicle.api.BrakeTelemetry
import com.motorguard.ivi.data.vehicle.api.Door
import com.motorguard.ivi.data.vehicle.api.DoorState
import com.motorguard.ivi.data.vehicle.api.DoorsTelemetry
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.RemainingLife
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import com.motorguard.ivi.data.vehicle.api.TireTelemetry
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.VehicleMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Phase-1 stand-in for the real vehicle. Emits plausible values on a believable
 * cadence (1–3 s), small random drift per tick, and supports debug overrides so
 * any hotspot can be forced into OK / CAUTION / CRITICAL / OFFLINE / STALE —
 * see FakeDataControlPanel. Never fabricates a value for OFFLINE (emits
 * SignalState.Offline and the UI shows "No data" + grey).
 *
 * This is the ONLY place with fake values; nothing in the app module may
 * reference this class outside of DI wiring (README-phase1-ui-brief §11).
 */
class FakeVehicleDataSource(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : VehicleDataSource {

    /** Debug-forced condition for one hotspot. AUTO = drift naturally. */
    enum class ForcedState { AUTO, OK, CAUTION, CRITICAL, OFFLINE, STALE }

    // ------------------------------------------------ mutable raw state

    private var batteryRaw = BatteryTelemetry(chargePercent = 62f, cellTempC = 28f, healthPercent = 96f, cycleCount = 312, charging = false)
    private var motorRaw = MotorTelemetry(
        faultType = MotorFaultType.NORMAL,
        faultSeverity = Severity.OK,
        remainingLife = RemainingLife(hours = 1240f, percent = 82f),
    )
    private var brakesRaw = BrakeTelemetry(padWearPercent = 42f, fluidOk = true)
    private var tiresRaw = mutableMapOf(
        Hotspot.TIRE_FL to TireTelemetry(Hotspot.TIRE_FL, psi = 34f, tempC = 21f),
        Hotspot.TIRE_FR to TireTelemetry(Hotspot.TIRE_FR, psi = 34.2f, tempC = 21f),
        Hotspot.TIRE_RL to TireTelemetry(Hotspot.TIRE_RL, psi = 33.8f, tempC = 20f),
        Hotspot.TIRE_RR to TireTelemetry(Hotspot.TIRE_RR, psi = 34f, tempC = 21f),
    )
    private var doorsRaw = DoorsTelemetry(
        Door.entries.map { DoorState(it, open = false, locked = true) },
    )
    private var metricsRaw = VehicleMetrics(speedKmh = 0f, odometerKm = 18_432f)

    // ------------------------------------------------ forced overrides (debug panel)

    private val forced = MutableStateFlow<Map<Hotspot, ForcedState>>(emptyMap())

    /** Public snapshot of forced states, for the debug panel UI. */
    val forcedSnapshotFlow: StateFlow<Map<Hotspot, ForcedState>> = forced

    /** Snapshot of the value at the moment a hotspot was forced to STALE (frozen for display). */
    private val staleSnapshots = mutableMapOf<Hotspot, SignalState<*>>()

    // ------------------------------------------------ exposed flows

    private val _battery = MutableStateFlow<SignalState<BatteryTelemetry>>(SignalState.Loading)
    override val battery: StateFlow<SignalState<BatteryTelemetry>> = _battery

    private val _motor = MutableStateFlow<SignalState<MotorTelemetry>>(SignalState.Loading)
    override val motor: StateFlow<SignalState<MotorTelemetry>> = _motor

    private val _brakes = MutableStateFlow<SignalState<BrakeTelemetry>>(SignalState.Loading)
    override val brakes: StateFlow<SignalState<BrakeTelemetry>> = _brakes

    private val _tires = MutableStateFlow<List<SignalState<TireTelemetry>>>(List(4) { SignalState.Loading })
    override val tires: StateFlow<List<SignalState<TireTelemetry>>> = _tires

    private val _doors = MutableStateFlow<SignalState<DoorsTelemetry>>(SignalState.Loading)
    override val doors: StateFlow<SignalState<DoorsTelemetry>> = _doors

    private val _metrics = MutableStateFlow<SignalState<VehicleMetrics>>(SignalState.Loading)
    override val metrics: StateFlow<SignalState<VehicleMetrics>> = _metrics

    init {
        // Per-tier cadence: motor is deliberately more volatile (README §6).
        scheduleHotspot(Hotspot.BATTERY, 2500L, jitterMs = 1200) { tickBattery() }
        scheduleHotspot(Hotspot.MOTOR, 900L, jitterMs = 500) { tickMotor() }
        scheduleHotspot(Hotspot.BRAKES, 3800L, jitterMs = 1800) { tickBrakes() }
        Hotspot.tireCorners.forEach { corner ->
            scheduleHotspot(corner, 2000L, jitterMs = 1000) { tickTire(corner) }
        }
        scheduleHotspot(Hotspot.DOORS, 4000L, jitterMs = 2000) { tickDoors() }
        scope.launch {
            while (true) {
                delay(1500L + Random.nextLong(1000))
                tickMetrics()
            }
        }
        // Republish when a forced state flips so the UI changes immediately.
        scope.launch {
            forced.collect { republishAll() }
        }
    }

    // ------------------------------------------------ debug panel API

    fun setForced(hotspot: Hotspot, state: ForcedState) {
        forced.value = forced.value + (hotspot to state)
        if (state == ForcedState.AUTO) {
            // resume live updates from current raw value
        }
        if (state == ForcedState.STALE) freezeStale(hotspot)
    }

    fun forcedState(hotspot: Hotspot): ForcedState = forced.value[hotspot] ?: ForcedState.AUTO

    fun resetAll() {
        forced.value = emptyMap()
        staleSnapshots.clear()
    }

    override fun reconnect() {
        staleSnapshots.clear()
        republishAll()
    }

    // ------------------------------------------------ ticks (AUTO drift)

    private fun tickBattery() {
        if (forced.value[Hotspot.BATTERY]?.let { it != ForcedState.AUTO } == true) return
        batteryRaw = batteryRaw.copy(
            chargePercent = (batteryRaw.chargePercent - Random.nextFloat() * 0.15f).coerceIn(5f, 100f),
            cellTempC = batteryRaw.cellTempC.drift(0.4f, 10f, 70f),
        )
    }

    /**
     * The motor publishes a classification, not a measurement, so there is nothing here that
     * drifts sample to sample. Remaining life creeps down instead, slowly enough to be invisible
     * within a session and fast enough that a long demo shows it moving.
     */
    private fun tickMotor() {
        if (forced.value[Hotspot.MOTOR]?.let { it != ForcedState.AUTO } == true) return
        val life = motorRaw.remainingLife ?: return
        motorRaw = motorRaw.copy(
            remainingLife = life.copy(
                hours = (life.hours - 0.05f).coerceAtLeast(0f),
                percent = life.percent?.let { (it - 0.004f).coerceAtLeast(0f) },
            ),
        )
    }

    private fun tickBrakes() {
        if (forced.value[Hotspot.BRAKES]?.let { it != ForcedState.AUTO } == true) return
        // mostly static, rarely changes (README §6)
        if (Random.nextInt(100) < 4) {
            brakesRaw = brakesRaw.copy(padWearPercent = (brakesRaw.padWearPercent + 1).coerceAtMost(98f))
        }
    }

    private fun tickTire(corner: Hotspot) {
        if (forced.value[corner]?.let { it != ForcedState.AUTO } == true) return
        val cur = tiresRaw.getValue(corner)
        tiresRaw[corner] = cur.copy(
            psi = cur.psi.drift(0.2f, 18f, 44f),
            tempC = cur.tempC.drift(0.3f, 5f, 55f),
        )
    }

    private fun tickDoors() {
        if (forced.value[Hotspot.DOORS]?.let { it != ForcedState.AUTO } == true) return
        // doors are toggled via debug panel, not spontaneously (README §6)
    }

    private fun tickMetrics() {
        // Road speed drifts on its own now that the motor publishes no speed. This is the status
        // bar's number, unrelated to the diagnostics motor.
        metricsRaw = metricsRaw.copy(
            speedKmh = metricsRaw.speedKmh.drift(4f, 0f, 120f),
        )
        _metrics.value = SignalState.Live(metricsRaw, clock())
    }

    // ------------------------------------------------ force / publish

    private fun freezeStale(hotspot: Hotspot) {
        staleSnapshots[hotspot] = currentSignal(hotspot)
    }

    private fun republishAll() {
        _battery.value = batterySignal()
        _motor.value = motorSignal()
        _brakes.value = brakesSignal()
        _tires.value = Hotspot.tireCorners.map { tireSignal(it) }
        _doors.value = doorsSignal()
    }

    private fun currentSignal(hotspot: Hotspot): SignalState<*> = when (hotspot) {
        Hotspot.BATTERY -> batterySignal()
        Hotspot.MOTOR -> motorSignal()
        Hotspot.BRAKES -> brakesSignal()
        Hotspot.DOORS -> doorsSignal()
        Hotspot.TIRE_FL, Hotspot.TIRE_FR, Hotspot.TIRE_RL, Hotspot.TIRE_RR -> tireSignal(hotspot)
    }

    private fun batterySignal(): SignalState<BatteryTelemetry> = when (forced.value[Hotspot.BATTERY]) {
        ForcedState.OFFLINE -> SignalState.Offline
        ForcedState.STALE -> staleSnapshots[Hotspot.BATTERY] as? SignalState.Stale<BatteryTelemetry>
            ?: (batterySignalNoForce().let { SignalState.Stale(requireData(it), clock() - 30_000) })
        ForcedState.OK -> SignalState.Live(
            BatteryTelemetry(chargePercent = 75f, cellTempC = 27f, healthPercent = 97f, cycleCount = 312, charging = false), clock())
        ForcedState.CAUTION -> SignalState.Live(
            batteryRaw.copy(chargePercent = 17f, cellTempC = 48f, healthPercent = 87f), clock())
        ForcedState.CRITICAL -> SignalState.Live(
            batteryRaw.copy(chargePercent = 7f, cellTempC = 63f, healthPercent = 74f), clock())
        else -> batterySignalNoForce()
    }

    private fun batterySignalNoForce(): SignalState<BatteryTelemetry> = SignalState.Live(batteryRaw, clock())

    private fun motorSignal(): SignalState<MotorTelemetry> = when (forced.value[Hotspot.MOTOR]) {
        ForcedState.OFFLINE -> SignalState.Offline
        ForcedState.STALE -> staleSnapshots[Hotspot.MOTOR] as? SignalState.Stale<MotorTelemetry>
            ?: SignalState.Stale(motorRaw, clock() - 25_000)
        ForcedState.OK -> SignalState.Live(
            MotorTelemetry(
                faultType = MotorFaultType.NORMAL,
                faultSeverity = Severity.OK,
                remainingLife = RemainingLife(hours = 1240f, percent = 82f),
            ),
            clock(),
        )
        ForcedState.CAUTION -> SignalState.Live(
            MotorTelemetry(
                faultType = MotorFaultType.MECHANICAL,
                faultSeverity = Severity.CAUTION,
                remainingLife = RemainingLife(hours = 310f, percent = 24f),
            ),
            clock(),
        )
        ForcedState.CRITICAL -> SignalState.Live(
            MotorTelemetry(
                faultType = MotorFaultType.ELECTRICAL,
                faultSeverity = Severity.CRITICAL,
                remainingLife = RemainingLife(hours = 38f, percent = 9f),
            ),
            clock(),
        )
        else -> SignalState.Live(motorRaw, clock())
    }

    private fun brakesSignal(): SignalState<BrakeTelemetry> = when (forced.value[Hotspot.BRAKES]) {
        ForcedState.OFFLINE -> SignalState.Offline
        ForcedState.STALE -> staleSnapshots[Hotspot.BRAKES] as? SignalState.Stale<BrakeTelemetry>
            ?: SignalState.Stale(brakesRaw, clock() - 40_000)
        ForcedState.OK -> SignalState.Live(BrakeTelemetry(padWearPercent = 30f, fluidOk = true), clock())
        ForcedState.CAUTION -> SignalState.Live(BrakeTelemetry(padWearPercent = 78f, fluidOk = true), clock())
        ForcedState.CRITICAL -> SignalState.Live(BrakeTelemetry(padWearPercent = 93f, fluidOk = false), clock())
        else -> SignalState.Live(brakesRaw, clock())
    }

    private fun tireSignal(corner: Hotspot): SignalState<TireTelemetry> = when (forced.value[corner]) {
        ForcedState.OFFLINE -> SignalState.Offline
        ForcedState.STALE -> staleSnapshots[corner] as? SignalState.Stale<TireTelemetry>
            ?: SignalState.Stale(tiresRaw.getValue(corner), clock() - 35_000)
        ForcedState.OK -> SignalState.Live(tireOf(corner, 34f), clock())
        ForcedState.CAUTION -> SignalState.Live(tireOf(corner, 27.5f), clock())
        ForcedState.CRITICAL -> SignalState.Live(tireOf(corner, 19f), clock())
        else -> SignalState.Live(tiresRaw.getValue(corner), clock())
    }

    private fun tireOf(corner: Hotspot, psi: Float) =
        TireTelemetry(corner, psi = psi, tempC = tiresRaw.getValue(corner).tempC)

    private fun doorsSignal(): SignalState<DoorsTelemetry> = when (forced.value[Hotspot.DOORS]) {
        ForcedState.OFFLINE -> SignalState.Offline
        ForcedState.STALE -> staleSnapshots[Hotspot.DOORS] as? SignalState.Stale<DoorsTelemetry>
            ?: SignalState.Stale(doorsRaw, clock() - 50_000)
        ForcedState.OK -> SignalState.Live(allDoors(open = false, locked = true), clock())
        ForcedState.CAUTION -> SignalState.Live(
            allDoors(open = false, locked = false), clock())
        ForcedState.CRITICAL -> SignalState.Live(
            allDoors(open = true, locked = false), clock())
        else -> SignalState.Live(doorsRaw, clock())
    }

    private fun allDoors(open: Boolean, locked: Boolean) =
        DoorsTelemetry(Door.entries.map { DoorState(it, open = open, locked = locked) })

    /** Debug-panel granular door control (does not go through forced states). */
    fun setDoor(door: Door, open: Boolean? = null, locked: Boolean? = null) {
        val updated = doorsRaw.doors.map {
            if (it.door == door) it.copy(open = open ?: it.open, locked = locked ?: it.locked) else it
        }
        doorsRaw = DoorsTelemetry(updated)
        if (forced.value[Hotspot.DOORS] == ForcedState.AUTO || forced.value[Hotspot.DOORS] == null) {
            _doors.value = SignalState.Live(doorsRaw, clock())
        }
    }

    // ------------------------------------------------ helpers

    private fun scheduleHotspot(
        @Suppress("unused") hotspot: Hotspot,
        baseMs: Long,
        jitterMs: Long,
        tick: suspend () -> Unit,
    ) {
        scope.launch {
            republishAll()
            while (true) {
                delay(baseMs + Random.nextLong(jitterMs.coerceAtLeast(1)))
                tick()
                republishAll()
            }
        }
    }

    private fun Float.drift(maxDelta: Float, min: Float, max: Float): Float =
        (this + (Random.nextFloat() * 2f - 1f) * maxDelta).coerceIn(min, max)

    @Suppress("UNCHECKED_CAST")
    private fun <T> requireData(s: SignalState<T>): T = when (s) {
        is SignalState.Live -> s.data as T
        is SignalState.Stale -> s.lastData as T
        else -> throw IllegalStateException("no data to freeze")
    }
}
