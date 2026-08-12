package com.motorguard.ivi.data.vehicle.api

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalStateTest {

    @Test
    fun `latestValueOrNull extracts live and stale, null for offline loading`() {
        val b = BatteryTelemetry(62f, 28f, 96f, 312, false)
        assertEquals(b, SignalState.Live(b, 10).latestValueOrNull)
        assertEquals(b, SignalState.Stale(b, 10).latestValueOrNull)
        assertNull(SignalState.Offline.latestValueOrNull)
        assertNull(SignalState.Loading.latestValueOrNull)
    }

    @Test
    fun `offline and loading report via isOfflineOrLoading`() {
        assert(SignalState.Offline.isOfflineOrLoading)
        assert(SignalState.Loading.isOfflineOrLoading)
        assert(!SignalState.Live(1, 1).isOfflineOrLoading)
        assert(!SignalState.Stale(1, 1).isOfflineOrLoading)
    }

    @Test
    fun `severity for tires derives per corner`() = runTest {
        val tires = MutableStateFlow<List<SignalState<TireTelemetry>>>(
            listOf(
                SignalState.Live(TireTelemetry(Hotspot.TIRE_FL, psi = 25f, tempC = 21f), 1),
                SignalState.Live(TireTelemetry(Hotspot.TIRE_FR, psi = 34f, tempC = 21f), 1),
                SignalState.Live(TireTelemetry(Hotspot.TIRE_RL, psi = 20f, tempC = 21f), 1),
                SignalState.Live(TireTelemetry(Hotspot.TIRE_RR, psi = 34f, tempC = 21f), 1),
            ),
        )
        val battery = MutableStateFlow<SignalState<BatteryTelemetry>>(
            SignalState.Live(BatteryTelemetry(62f, 28f, 96f, 312, false), 1),
        )
        val motor = MutableStateFlow<SignalState<MotorTelemetry>>(
            SignalState.Live(
                MotorTelemetry(
                    rpm = 0,
                    powerKw = 0f,
                    dcBusVolts = 400f,
                    faultType = MotorFaultType.NORMAL,
                    faultSeverity = Severity.OK,
                    remainingLife = RemainingLife(hours = 1240f),
                ),
                1,
            ),
        )
        val brakes = MutableStateFlow<SignalState<BrakeTelemetry>>(
            SignalState.Live(BrakeTelemetry(42f, fluidOk = true), 1),
        )
        val doors = MutableStateFlow<SignalState<DoorsTelemetry>>(
            SignalState.Live(DoorsTelemetry(Door.entries.map { DoorState(it, open = false, locked = true) }), 1),
        )
        val metrics = MutableStateFlow<SignalState<VehicleMetrics>>(SignalState.Loading)

        val source = object : VehicleDataSource {
            override val battery = battery
            override val motor = motor
            override val brakes = brakes
            override val tires = tires
            override val doors = doors
            override val metrics = metrics
        }

        val flowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sev = source.severityFlow(SeverityResolver())
        try {
            sev.severities.test {
                val first = awaitItem()
                assertEquals(Severity.CAUTION, first[Hotspot.TIRE_FL])
                assertEquals(Severity.CRITICAL, first[Hotspot.TIRE_RL])
                assertEquals(Severity.OK, first[Hotspot.TIRE_FR])
                assertEquals(Severity.OK, first[Hotspot.BATTERY])
                assertEquals(Severity.OK, first[Hotspot.DOORS])

                // Take FL offline -> severity becomes null (no fabrication)
                tires.value = listOf(
                    SignalState.Offline,
                    SignalState.Live(TireTelemetry(Hotspot.TIRE_FR, psi = 34f, tempC = 21f), 1),
                    SignalState.Live(TireTelemetry(Hotspot.TIRE_RL, psi = 20f, tempC = 21f), 1),
                    SignalState.Live(TireTelemetry(Hotspot.TIRE_RR, psi = 34f, tempC = 21f), 1),
                )
                val second = awaitItem()
                assertNull(second[Hotspot.TIRE_FL])
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            flowScope.cancel()
        }
    }
}
