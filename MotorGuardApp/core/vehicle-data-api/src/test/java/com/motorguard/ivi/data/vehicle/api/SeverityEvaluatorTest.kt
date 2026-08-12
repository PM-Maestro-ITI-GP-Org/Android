package com.motorguard.ivi.data.vehicle.api

import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityEvaluatorTest {

    private val evaluator = SeverityEvaluator()

    // ------------------------------------------------ raw threshold mapping

    @Test
    fun `tire psi maps ok caution critical`() {
        assertEquals(Severity.OK, evaluator.curb(34f))
        assertEquals(Severity.OK, evaluator.curb(28.1f))
        assertEquals(Severity.CAUTION, evaluator.curb(28f))
        assertEquals(Severity.CAUTION, evaluator.curb(25f))
        assertEquals(Severity.CRITICAL, evaluator.curb(24f))
        assertEquals(Severity.CRITICAL, evaluator.curb(10f))
    }

    @Test
    fun `battery charge maps ok caution critical`() {
        assertEquals(Severity.OK, evaluator.batteryCharge(62f))
        assertEquals(Severity.CAUTION, evaluator.batteryCharge(20f))
        assertEquals(Severity.CRITICAL, evaluator.batteryCharge(10f))
        assertEquals(Severity.CRITICAL, evaluator.batteryCharge(3f))
    }

    @Test
    fun `battery health maps ok caution critical`() {
        assertEquals(Severity.OK, evaluator.batteryHealth(96f))
        assertEquals(Severity.CAUTION, evaluator.batteryHealth(90f))
        assertEquals(Severity.CRITICAL, evaluator.batteryHealth(80f))
    }

    @Test
    fun `cell temp high-is-worse`() {
        assertEquals(Severity.OK, evaluator.cellTemp(28f))
        assertEquals(Severity.CAUTION, evaluator.cellTemp(45f))
        assertEquals(Severity.CRITICAL, evaluator.cellTemp(60f))
    }

    // The motor's load and temperature cases that used to live here are gone with the sensors:
    // this vehicle measures neither, and the motor's severity now arrives already classified.
    // SeverityResolverMotorTest covers what replaced them.

    @Test
    fun `brake wear and fluid`() {
        assertEquals(Severity.OK, evaluator.brakeWear(42f))
        assertEquals(Severity.CAUTION, evaluator.brakeWear(70f))
        assertEquals(Severity.CRITICAL, evaluator.brakeWear(90f))
        assertEquals(Severity.OK, evaluator.brakeFluid(true))
        assertEquals(Severity.CRITICAL, evaluator.brakeFluid(false))
    }

    @Test
    fun `doors aggregate`() {
        val closed = DoorsTelemetry(Door.entries.map { DoorState(it, open = false, locked = true) })
        // front doors locked, rear doors unlocked -> CAUTION (any unlocked)
        val unlocked = DoorsTelemetry(
            Door.entries.map { DoorState(it, open = false, locked = it.name.startsWith("F")) },
        )
        // front-left door open -> CRITICAL
        val open = DoorsTelemetry(
            Door.entries.map { DoorState(it, open = it == Door.FL, locked = it != Door.FL) },
        )
        assertEquals(Severity.OK, evaluator.doors(closed))
        assertEquals(Severity.CAUTION, evaluator.doors(unlocked))
        assertEquals(Severity.CRITICAL, evaluator.doors(open))
    }

    // ------------------------------------------------ hysteresis

    @Test
    fun `hysteresis holds caution until value recovers past margin`() {
        val r = SeverityResolver(evaluator)
        // escalate into caution (below 28)
        assertEquals(Severity.CAUTION, r.tirePsi(Hotspot.TIRE_FL, 27.5f))
        // recovery but still within margin (28 + 1) -> stays caution
        assertEquals(Severity.CAUTION, r.tirePsi(Hotspot.TIRE_FL, 28.3f))
        assertEquals(Severity.CAUTION, r.tirePsi(Hotspot.TIRE_FL, 28.9f))
        // clears margin fully -> OK
        assertEquals(Severity.OK, r.tirePsi(Hotspot.TIRE_FL, 29.2f))
    }

    @Test
    fun `hysteresis escalates freely but never de-escalates inside margin`() {
        val r = SeverityResolver(evaluator)
        assertEquals(Severity.OK, r.batteryCharge(50f))
        assertEquals(Severity.CAUTION, r.batteryCharge(18f)) // escalate OK->CAUTION
        assertEquals(Severity.CAUTION, r.batteryCharge(20.5f)) // within margin -> hold
        assertEquals(Severity.OK, r.batteryCharge(23.5f)) // cleared by margin -> OK
    }

    @Test
    fun `critical to ok path also uses margin on the way up`() {
        val r = SeverityResolver(evaluator)
        assertEquals(Severity.CRITICAL, r.tirePsi(Hotspot.TIRE_FR, 20f))
        assertEquals(Severity.CRITICAL, r.tirePsi(Hotspot.TIRE_FR, 24.5f)) // within crit margin
        assertEquals(Severity.CAUTION, r.tirePsi(Hotspot.TIRE_FR, 25.5f))  // past crit margin, within caution band
        assertEquals(Severity.CAUTION, r.tirePsi(Hotspot.TIRE_FR, 28.5f))  // within caution margin
        assertEquals(Severity.OK, r.tirePsi(Hotspot.TIRE_FR, 34f))
    }

    @Test
    fun `independent hotspots keep separate hysteresis memory`() {
        val r = SeverityResolver(evaluator)
        assertEquals(Severity.CAUTION, r.tirePsi(Hotspot.TIRE_RL, 26f))
        assertEquals(Severity.OK, r.tirePsi(Hotspot.TIRE_RR, 34f))
        // RL still caution with held memory; RR unaffected
        assertEquals(Severity.CAUTION, r.tirePsi(Hotspot.TIRE_RL, 28.5f))
        assertEquals(Severity.OK, r.tirePsi(Hotspot.TIRE_RR, 33f))
    }
}
