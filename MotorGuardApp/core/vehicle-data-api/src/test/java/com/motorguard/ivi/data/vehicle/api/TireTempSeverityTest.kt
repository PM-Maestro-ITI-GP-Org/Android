package com.motorguard.ivi.data.vehicle.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tire carcass temperature: OK below 70 C, CAUTION 70-85, CRITICAL above 85 (phase-1 brief §4.3).
 * Boundaries are covered exactly and one step either side, because a threshold that is off by one
 * comparison operator is the kind of bug that only shows up on a hot bench day.
 */
class TireTempSeverityTest {

    private val evaluator = SeverityEvaluator()

    @Test
    fun `tire temp boundaries`() {
        assertEquals(Severity.OK, evaluator.tireTemp(21f))
        assertEquals(Severity.OK, evaluator.tireTemp(69.9f))
        assertEquals(Severity.CAUTION, evaluator.tireTemp(70f))
        assertEquals(Severity.CAUTION, evaluator.tireTemp(84.9f))
        assertEquals(Severity.CRITICAL, evaluator.tireTemp(85f))
        assertEquals(Severity.CRITICAL, evaluator.tireTemp(120f))
    }

    @Test
    fun `hot tire raises the corner even at nominal pressure`() {
        val resolver = SeverityResolver()
        // 34 psi is dead-on the recommended pressure, so pressure alone would report OK.
        assertEquals(
            Severity.OK,
            resolver.severityFor(Hotspot.TIRE_FL, tirePsi = 34f, tireTempC = 20f),
        )
        assertEquals(
            Severity.CRITICAL,
            resolver.severityFor(Hotspot.TIRE_FR, tirePsi = 34f, tireTempC = 92f),
        )
    }

    @Test
    fun `omitting temperature keeps the pressure-only behaviour`() {
        val resolver = SeverityResolver()
        assertEquals(Severity.OK, resolver.severityFor(Hotspot.TIRE_RL, tirePsi = 34f))
        assertEquals(Severity.CRITICAL, resolver.severityFor(Hotspot.TIRE_RR, tirePsi = 19f))
    }

    @Test
    fun `temperature hysteresis holds until the value clears the margin`() {
        val resolver = SeverityResolver()
        // Escalates immediately on crossing.
        assertEquals(Severity.CAUTION, resolver.tireTemp(Hotspot.TIRE_FL, 70f))
        // 69 is below the threshold but inside the 2 C de-escalation margin, so it holds.
        assertEquals(Severity.CAUTION, resolver.tireTemp(Hotspot.TIRE_FL, 69f))
        // Clear of the margin — now it may recover.
        assertEquals(Severity.OK, resolver.tireTemp(Hotspot.TIRE_FL, 67f))
    }

    @Test
    fun `corners keep independent hysteresis state`() {
        val resolver = SeverityResolver()
        resolver.tireTemp(Hotspot.TIRE_FL, 90f)
        // A different corner must not inherit FL's escalation.
        assertEquals(Severity.OK, resolver.tireTemp(Hotspot.TIRE_RR, 20f))
    }
}
