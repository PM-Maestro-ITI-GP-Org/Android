package com.motorguard.ivi.data.vehicle.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The motor's severity is reported by the diagnostics unit, not derived here. These tests exist
 * because that is a rule about what this app is allowed to decide, and a rule nothing enforces is
 * a comment.
 */
class MotorSeverityTest {

    private val resolver = SeverityResolver()

    private fun motor(
        severity: Severity,
        fault: MotorFaultType = MotorFaultType.NORMAL,
        rpm: Int = 3120,
    ) = MotorTelemetry(
        rpm = rpm,
        powerKw = 12.4f,
        dcBusVolts = 396f,
        faultType = fault,
        faultSeverity = severity,
        remainingLife = RemainingLife(hours = 1240f, percent = 82f),
    )

    @Test
    fun `severity is whatever the diagnostics unit reported`() {
        Severity.entries.forEach { reported ->
            assertEquals(reported, resolver.motor(motor(reported)))
        }
    }

    @Test
    fun `hotspot severity for the motor is the reported severity`() {
        assertEquals(
            Severity.CRITICAL,
            resolver.severityFor(
                Hotspot.MOTOR,
                motor = motor(Severity.CRITICAL, MotorFaultType.ELECTRICAL),
            ),
        )
    }

    /**
     * Every other signal gets hysteresis so a value on the boundary cannot flicker the dot. A
     * classification has no boundary to sit on, so alternating reports must be followed exactly —
     * damping them would mean the car showed one severity while the unit that made the call
     * reported another.
     */
    @Test
    fun `alternating reports are followed exactly, with no hysteresis`() {
        assertEquals(Severity.CRITICAL, resolver.motor(motor(Severity.CRITICAL)))
        assertEquals(Severity.OK, resolver.motor(motor(Severity.OK)))
        assertEquals(Severity.CRITICAL, resolver.motor(motor(Severity.CRITICAL)))
        assertEquals(Severity.CAUTION, resolver.motor(motor(Severity.CAUTION)))
        assertEquals(Severity.OK, resolver.motor(motor(Severity.OK)))
    }

    /** Speed, power and bus voltage have no thresholds — a fast motor is not a faulty one. */
    @Test
    fun `live summary values do not affect severity`() {
        assertEquals(Severity.OK, resolver.motor(motor(Severity.OK, rpm = 9600)))
        assertEquals(Severity.OK, resolver.motor(motor(Severity.OK, rpm = 0)))
    }
}
