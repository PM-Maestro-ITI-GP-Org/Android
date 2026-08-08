package com.motorguard.ivi.ui.diagnostics

import com.motorguard.ivi.data.vehicle.api.Hotspot
import com.motorguard.ivi.data.vehicle.api.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert list's dismiss and re-alert rules (phase-1 brief §10). These are the rules most likely
 * to be quietly broken by a later refactor, and they are pure functions, so they are cheap to pin
 * down here rather than by tapping through the emulator.
 */
class AlertTrackingTest {

    private val t0 = 1_000L

    private fun trackingOf(vararg pairs: Pair<Hotspot, Severity?>): AlertTracking =
        AlertTracking().advance(mapOf(*pairs), t0)

    @Test
    fun `only caution and critical become alerts`() {
        val tracking = trackingOf(
            Hotspot.BATTERY to Severity.OK,
            Hotspot.MOTOR to Severity.CAUTION,
            Hotspot.BRAKES to Severity.CRITICAL,
            Hotspot.DOORS to null, // no signal
        )
        val alerts = tracking.visibleAlerts(emptyMap())
        assertEquals(listOf(Hotspot.BRAKES, Hotspot.MOTOR), alerts.map { it.hotspot })
    }

    @Test
    fun `critical sorts above caution regardless of age`() {
        val old = AlertTracking().advance(mapOf(Hotspot.BRAKES to Severity.CRITICAL), t0)
        val both = old.advance(
            mapOf(Hotspot.BRAKES to Severity.CRITICAL, Hotspot.MOTOR to Severity.CAUTION),
            t0 + 5_000L,
        )
        // MOTOR changed more recently, but CRITICAL still leads.
        assertEquals(
            listOf(Hotspot.BRAKES, Hotspot.MOTOR),
            both.visibleAlerts(emptyMap()).map { it.hotspot },
        )
    }

    @Test
    fun `equal severity sorts most recently changed first`() {
        val first = AlertTracking().advance(mapOf(Hotspot.MOTOR to Severity.CAUTION), t0)
        val second = first.advance(
            mapOf(Hotspot.MOTOR to Severity.CAUTION, Hotspot.BRAKES to Severity.CAUTION),
            t0 + 5_000L,
        )
        assertEquals(
            listOf(Hotspot.BRAKES, Hotspot.MOTOR),
            second.visibleAlerts(emptyMap()).map { it.hotspot },
        )
    }

    @Test
    fun `dismissing hides the row`() {
        val tracking = trackingOf(Hotspot.MOTOR to Severity.CAUTION)
        val dismissed = mapOf(Hotspot.MOTOR to Severity.CAUTION)
        assertTrue(tracking.visibleAlerts(dismissed).isEmpty())
    }

    @Test
    fun `escalation resurfaces a dismissed row`() {
        val dismissed = mapOf(Hotspot.MOTOR to Severity.CAUTION)
        val escalated = trackingOf(Hotspot.MOTOR to Severity.CRITICAL)
        val alerts = escalated.visibleAlerts(dismissed)
        assertEquals(listOf(Hotspot.MOTOR), alerts.map { it.hotspot })
        assertEquals(Severity.CRITICAL, alerts.single().severity)
    }

    @Test
    fun `dismissing at critical stays dismissed at critical`() {
        val dismissed = mapOf(Hotspot.MOTOR to Severity.CRITICAL)
        assertTrue(trackingOf(Hotspot.MOTOR to Severity.CRITICAL).visibleAlerts(dismissed).isEmpty())
    }

    @Test
    fun `de-escalation does not resurface a row dismissed at the higher severity`() {
        val dismissed = mapOf(Hotspot.MOTOR to Severity.CRITICAL)
        // Improving from CRITICAL to CAUTION is not news the driver needs re-raised.
        assertTrue(trackingOf(Hotspot.MOTOR to Severity.CAUTION).visibleAlerts(dismissed).isEmpty())
    }

    @Test
    fun `severity change stamps a new time only for the hotspot that moved`() {
        val first = AlertTracking().advance(
            mapOf(Hotspot.MOTOR to Severity.CAUTION, Hotspot.BRAKES to Severity.CAUTION),
            t0,
        )
        val second = first.advance(
            mapOf(Hotspot.MOTOR to Severity.CRITICAL, Hotspot.BRAKES to Severity.CAUTION),
            t0 + 9_000L,
        )
        assertEquals(t0 + 9_000L, second.changedAt[Hotspot.MOTOR])
        assertEquals(t0, second.changedAt[Hotspot.BRAKES])
    }

    @Test
    fun `repeated identical emissions do not restamp the change time`() {
        val first = AlertTracking().advance(mapOf(Hotspot.MOTOR to Severity.CAUTION), t0)
        val again = first.advance(mapOf(Hotspot.MOTOR to Severity.CAUTION), t0 + 30_000L)
        assertEquals(t0, again.changedAt[Hotspot.MOTOR])
    }

    /**
     * The pruning half of the rule lives in the ViewModel (a dismissal is dropped once the hotspot
     * returns to OK). This pins the consequence the pruning is there to produce: with the record
     * gone, a fresh degrade to the SAME severity must alert again.
     */
    @Test
    fun `degrading again after recovery alerts when the dismissal was pruned`() {
        val tracking = trackingOf(Hotspot.MOTOR to Severity.CAUTION)
        assertEquals(listOf(Hotspot.MOTOR), tracking.visibleAlerts(emptyMap()).map { it.hotspot })
    }
}
