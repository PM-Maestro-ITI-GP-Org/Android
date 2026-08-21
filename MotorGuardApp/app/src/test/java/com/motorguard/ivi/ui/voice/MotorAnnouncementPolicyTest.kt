package com.motorguard.ivi.ui.voice

import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the car is allowed to speak without being asked.
 *
 * Every rule here is one the driver would notice being wrong, and none of them are observable
 * from a single reading — they are all about what came before. Hence a clock parameter and no
 * device: the interesting cases are a fault that persists, a fault that flickers, and a link
 * that drops and returns, none of which are convenient to produce in a car.
 */
class MotorAnnouncementPolicyTest {

    private val dwell = 3_000L
    private var now = 1_700_000_000_000L
    private val policy = MotorAnnouncementPolicy(dwellMs = dwell)

    private fun motor(
        fault: MotorFaultType = MotorFaultType.ELECTRICAL,
        severity: Severity = Severity.CAUTION,
    ) = MotorTelemetry(fault, severity, null)

    /** One reading, `ms` after the last. */
    private fun tick(t: MotorTelemetry?, ms: Long = 1_000L): MotorTelemetry? {
        now += ms
        val state = t?.let { SignalState.Live(it, now) } ?: SignalState.Offline
        return policy.onState(state, now)
    }

    /** Push the same reading until the dwell has elapsed; returns whatever it announced. */
    private fun settle(t: MotorTelemetry): MotorTelemetry? {
        tick(t, 0)
        var announced: MotorTelemetry? = null
        repeat(4) { announced = announced ?: tick(t) }
        return announced
    }

    @Test
    fun `announces a fault once it has held`() {
        assertNotNull(settle(motor()))
    }

    /** The whole point of the dwell: a reading that has not settled says nothing yet. */
    @Test
    fun `says nothing before the dwell has elapsed`() {
        assertNull(tick(motor(), 0))
        assertNull(tick(motor(), dwell - 1))
    }

    /** A fault that is merely still there is not news. */
    @Test
    fun `does not repeat an ongoing fault`() {
        assertNotNull(settle(motor()))
        repeat(30) { assertNull(tick(motor())) }
    }

    /**
     * A classifier oscillating near a boundary must not make the car talk. docs/10 3.4 warns it
     * will flicker the dot; this is the same flicker with a voice attached.
     */
    @Test
    fun `a flickering classification never announces`() {
        repeat(20) {
            assertNull(tick(motor(MotorFaultType.ELECTRICAL, Severity.CAUTION), 500))
            assertNull(tick(motor(MotorFaultType.NORMAL, Severity.OK), 500))
        }
    }

    @Test
    fun `escalation is worth saying again`() {
        assertNotNull(settle(motor(severity = Severity.CAUTION)))
        val escalated = settle(motor(severity = Severity.CRITICAL))
        assertEquals(Severity.CRITICAL, escalated?.faultSeverity)
    }

    /** Nobody needs telling the car is less broken than it was. */
    @Test
    fun `de-escalation is silent`() {
        assertNotNull(settle(motor(severity = Severity.CRITICAL)))
        repeat(10) { assertNull(tick(motor(severity = Severity.CAUTION))) }
    }

    /** A different fault is a different thing to say, even at the same severity. */
    @Test
    fun `a change of fault type announces`() {
        assertNotNull(settle(motor(MotorFaultType.ELECTRICAL, Severity.CAUTION)))
        val next = settle(motor(MotorFaultType.MECHANICAL, Severity.CAUTION))
        assertEquals(MotorFaultType.MECHANICAL, next?.faultType)
    }

    /** Cleared and returned is a new event, and worth hearing — FaultTone's rule. */
    @Test
    fun `a fault that clears and comes back announces again`() {
        assertNotNull(settle(motor()))
        assertNull(tick(motor(MotorFaultType.NORMAL, Severity.OK)))
        assertNotNull(settle(motor()))
    }

    /** An old or unverifiable reading is the card's business, not something to say aloud. */
    @Test
    fun `never announces from a signal that is not live`() {
        val t = motor()
        repeat(10) {
            now += 1_000L
            assertNull(policy.onState(SignalState.Stale(t, now - 60_000L), now))
            assertNull(policy.onState(SignalState.Offline, now))
            assertNull(policy.onState(SignalState.Loading, now))
        }
    }

    /**
     * The reconnect case. A link that drops and returns with the fault it left with must not
     * greet the driver with something they were told about ten minutes ago.
     */
    @Test
    fun `a reconnect carrying the same fault stays quiet`() {
        assertNotNull(settle(motor()))
        repeat(5) { tick(null) }              // link down
        repeat(10) { assertNull(tick(motor())) }
    }

    /** ...but a link that returns with something worse still speaks up. */
    @Test
    fun `a reconnect carrying a worse fault still announces`() {
        assertNotNull(settle(motor(severity = Severity.CAUTION)))
        repeat(5) { tick(null) }
        assertNotNull(settle(motor(severity = Severity.CRITICAL)))
    }

    /**
     * A severity raised with no fault type is the unit contradicting itself, and it is still a
     * raised alert — the one reading where staying quiet would be the dangerous choice.
     */
    @Test
    fun `a severity without a fault type still announces`() {
        assertNotNull(settle(motor(MotorFaultType.NORMAL, Severity.CRITICAL)))
    }
}
