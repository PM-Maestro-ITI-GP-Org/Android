package com.motorguard.ivi.ui.diagnostics

import com.motorguard.ivi.data.vehicle.api.Door
import com.motorguard.ivi.data.vehicle.api.DoorState
import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorCaptureSource
import com.motorguard.ivi.data.vehicle.api.VehicleDataSource
import com.motorguard.ivi.data.vehicle.api.latestValueOrNull
import com.motorguard.ivi.data.vehicle.fake.FakeMotorCaptureSource
import com.motorguard.ivi.data.vehicle.fake.FakeVehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UI-layer mirror of [FakeVehicleDataSource.ForcedState], so the fake type never leaks past
 * this file (spec §15 — see [VehicleData]'s KDoc).
 */
enum class ForcedCondition { AUTO, OK, CAUTION, CRITICAL, OFFLINE, STALE }

/**
 * The single justified exception to "the UI must not know about the fake source": the debug
 * panel exists purely to drive the fake, and only the fake, so it needs *some* control surface.
 * Expressed as a UI-owned interface so that (a) [com.motorguard.ivi.ui.diagnostics.debug.FakeDataControlPanel]
 * compiles against no fake type at all, and (b) a Phase 2 build can bind this to nothing (debug
 * panel hidden/unused) or to a real `CarPropertyManager` injector, by changing only [VehicleData].
 */
interface VehicleDebugControls {
    val forced: StateFlow<Map<Hotspot, ForcedCondition>>
    val doorStates: StateFlow<List<DoorState>>
    fun force(hotspot: Hotspot, condition: ForcedCondition)
    fun setDoor(door: Door, open: Boolean? = null, locked: Boolean? = null)
    fun resetAll()
}

/**
 * The **only** file in the app module allowed to name [FakeVehicleDataSource] (enforced by
 * `grep -rn "FakeVehicleDataSource" app/src/main/java/com/motorguard/ivi/ui/ | grep -v
 * VehicleData.kt`, which must stay empty). Everything else — [DiagnosticsViewModel], the car
 * renderer, the overlay — depends on [VehicleDataSource] / [VehicleDebugControls] only.
 */
object VehicleData {
    /**
     * Process-lifetime, deliberately never cancelled. Why here and not a ViewModel or an
     * Application subclass:
     *  - Must OUTLIVE the fragment: `MainActivity.show()` replaces the fragment on every tab
     *    switch, so a fragment/VM-scoped source would restart the drift and silently drop every
     *    forced debug state mid-demo.
     *  - Must NOT outlive the process, and does not: it dies with the process.
     *  - The spec forbids touching `AndroidManifest.xml`, so there is no Application subclass to
     *    hang this off instead.
     *  - The classic static-scope hazard is leaking a Context/Activity/View. This object holds
     *    NONE of those — only a [CoroutineScope] and pure-Kotlin data — so it cannot leak the
     *    Activity.
     *  - `Dispatchers.Main.immediate`, not `Default`: [FakeVehicleDataSource] mutates plain
     *    unsynchronised `var` fields from its ticker coroutines. Main-thread confinement is what
     *    makes that safe without adding locks, and `.immediate` removes a dispatch hop before
     *    every `StateFlow` emission the UI observes.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fake = FakeVehicleDataSource(scope)

    /**
     * Capture requests against the fake. Synthesis runs on [Dispatchers.Default] rather than the
     * main-thread scope above: 200,000 samples across twelve channels is real arithmetic, and the
     * popup is animating while it happens. The fault type is read from the live motor signal at
     * request time so the captured waveform shows the defect the card is reporting rather than a
     * healthy motor.
     */
    private val fakeCapture: MotorCaptureSource = FakeMotorCaptureSource(
        computeContext = Dispatchers.Default,
        faultTypeProvider = { fake.motor.value.latestValueOrNull?.faultType ?: MotorFaultType.NORMAL },
    )

    /**
     * Everything else in the UI sees only this type.
     *
     * The fake, on this branch, always. The SOME/IP link is added by the AOSP drop-in — its
     * `motorservice/vehicledata-someip.patch` rewrites exactly these two properties at deploy
     * time, which is the only place the Kotlin for it and `libmotorguardsomeip.so` both exist.
     * Writing that wiring here instead means Gradle compiling a reference to a package that is
     * not in this tree, which is what it did.
     */
    val source: VehicleDataSource = fake

    /** The debug panel's only window into [fake]. Still the fake even when the link is up: it
     *  drives the five signals the diagnostics unit does not publish, and forcing the motor from
     *  here would be overriding a real reading with a made-up one. */
    val debugControls: VehicleDebugControls = FakeDebugControls(fake, scope)

    val captureSource: MotorCaptureSource = fakeCapture

    /**
     * "Open Diagnostics and focus the battery" from outside the fragment — [MainActivity]'s
     * fault reaction, which fires whether or not [DiagnosticsViewModel] happens to exist yet.
     *
     * A [StateFlow], not a plain field: [DiagnosticsViewModel] is fragment-scoped and dies on
     * tab switch (see its own KDoc), so the request has to survive here, process-lifetime, next
     * to [source] for the same reason. A `StateFlow` replays its current value to a NEW
     * collector, which is what makes this work identically whether the fragment is created
     * *after* the request is set (driver was elsewhere) or was already collecting it (driver was
     * already on Diagnostics) — no separate "already open" branch is needed at the call site.
     * Set back to null once read, so a later tab visit with no new fault does not re-focus.
     */
    val focusRequest = MutableStateFlow<Hotspot?>(null)
}

private class FakeDebugControls(
    private val fake: FakeVehicleDataSource,
    scope: CoroutineScope,
) : VehicleDebugControls {
    override val forced: StateFlow<Map<Hotspot, ForcedCondition>> =
        fake.forcedSnapshotFlow.map { m -> m.mapValues { (_, v) -> v.toUi() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** `filterNotNull` keeps the last known door list when DOORS is forced OFFLINE, rather than
     *  collapsing the debug panel's own door rows to nothing. */
    override val doorStates: StateFlow<List<DoorState>> =
        fake.doors.map { it.latestValueOrNull?.doors }.filterNotNull()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                Door.entries.map { DoorState(it, open = false, locked = true) },
            )

    override fun force(hotspot: Hotspot, condition: ForcedCondition) =
        fake.setForced(hotspot, condition.toFake())

    override fun setDoor(door: Door, open: Boolean?, locked: Boolean?) =
        fake.setDoor(door, open, locked)

    override fun resetAll() = fake.resetAll()
}

// Explicit `when`s, not enumValueOf(name): the compiler then breaks the build if either enum
// ever gains a constant the other lacks, instead of silently throwing at runtime.
private fun FakeVehicleDataSource.ForcedState.toUi(): ForcedCondition = when (this) {
    FakeVehicleDataSource.ForcedState.AUTO -> ForcedCondition.AUTO
    FakeVehicleDataSource.ForcedState.OK -> ForcedCondition.OK
    FakeVehicleDataSource.ForcedState.CAUTION -> ForcedCondition.CAUTION
    FakeVehicleDataSource.ForcedState.CRITICAL -> ForcedCondition.CRITICAL
    FakeVehicleDataSource.ForcedState.OFFLINE -> ForcedCondition.OFFLINE
    FakeVehicleDataSource.ForcedState.STALE -> ForcedCondition.STALE
}

private fun ForcedCondition.toFake(): FakeVehicleDataSource.ForcedState = when (this) {
    ForcedCondition.AUTO -> FakeVehicleDataSource.ForcedState.AUTO
    ForcedCondition.OK -> FakeVehicleDataSource.ForcedState.OK
    ForcedCondition.CAUTION -> FakeVehicleDataSource.ForcedState.CAUTION
    ForcedCondition.CRITICAL -> FakeVehicleDataSource.ForcedState.CRITICAL
    ForcedCondition.OFFLINE -> FakeVehicleDataSource.ForcedState.OFFLINE
    ForcedCondition.STALE -> FakeVehicleDataSource.ForcedState.STALE
}
