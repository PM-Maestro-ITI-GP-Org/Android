package com.motorguard.ivi.ui.voice

import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.Maneuver
import com.motorguard.ivi.data.nav.NavProgress
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.data.nav.RouteStep
import com.motorguard.ivi.ui.nav.NavPhase
import com.motorguard.ivi.ui.nav.NavUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trip questions, and the states where there is no honest figure to give.
 *
 * The interesting cases are the ones with no number: not navigating at all, and a route that has
 * started but has no position yet. Those are a second apart on screen and easy to conflate in a
 * sentence, and conflating them means telling a driver there is no route when there is one.
 */
class NavVoiceTest {

    private fun progress(
        remainingMeters: Double = 4_200.0,
        remainingSeconds: Double = 480.0,
        arrived: Boolean = false,
    ) = NavProgress(
        currentStep = step(),
        followingStep = null,
        distanceToManeuverMeters = 120.0,
        remainingDistanceMeters = remainingMeters,
        remainingDurationSeconds = remainingSeconds,
        fractionTraveled = 0.3f,
        traveledShapeIndex = 0,
        arrived = arrived,
    )

    private fun guiding(p: NavProgress?) =
        NavUiState(phase = NavPhase.Guiding(route = route(), progress = p))

    private fun step() = RouteStep(
        maneuver = Maneuver.CONTINUE, instruction = "Continue", roadName = "Corniche El Nil",
        distanceMeters = 900.0, durationSeconds = 90.0, shapeStart = 0, shapeEnd = 1,
    )

    private fun route() = Route(
        id = "r1", label = "Fastest", distanceMeters = 6_000.0, durationSeconds = 700.0,
        shape = listOf(GeoPoint(30.0, 31.2), GeoPoint(30.1, 31.3)), steps = listOf(step()),
        destination = Place(name = "Work", subtitle = "", point = GeoPoint(30.1, 31.3)),
    )

    private fun ask(u: String) = NavVoice.intentOf(u)

    @Test
    fun `trip questions are recognised`() {
        assertEquals(NavVoice.Ask.ETA, ask("how long until we arrive"))
        assertEquals(NavVoice.Ask.ETA, ask("are we nearly there"))
        assertEquals(NavVoice.Ask.ETA, ask("when will we get there"))
        assertEquals(NavVoice.Ask.DISTANCE, ask("how far is it"))
        assertEquals(NavVoice.Ask.CANCEL, ask("cancel the route"))
        assertEquals(NavVoice.Ask.CANCEL, ask("stop navigating"))
    }

    @Test
    fun `leaves everything else alone`() {
        listOf("play some music", "take me home", "is the motor okay", "", "call the office")
            .forEach { assertNull(it, ask(it)) }
    }

    // --- setting a destination ----------------------------------------------

    @Test
    fun `a destination is lifted out of the phrasing`() {
        assertEquals("cairo airport", NavVoice.destinationOf("take me to Cairo Airport"))
        assertEquals("maadi", NavVoice.destinationOf("navigate to Maadi"))
        assertEquals("nearest petrol station", NavVoice.destinationOf("directions to the nearest petrol station"))
        assertEquals("city centre", NavVoice.destinationOf("let's go to the city centre"))
    }

    /**
     * The two most natural things to say, and the two this app cannot answer: no address is
     * stored for either, so resolving them would search for the literal word and drive to a pub
     * called Home with full confidence. They keep routing to the tab.
     */
    @Test
    fun `home and work are refused rather than searched for`() {
        assertNull(NavVoice.destinationOf("take me home"))
        assertNull(NavVoice.destinationOf("drive me home"))
        assertNull(NavVoice.destinationOf("navigate to work"))
        assertNull(NavVoice.destinationOf("take me to the office"))
    }

    /** Anchored to the start, so a distance question is not mistaken for a new destination. */
    @Test
    fun `questions that merely mention a place are not destinations`() {
        assertNull(NavVoice.destinationOf("how far is it to the airport"))
        assertNull(NavVoice.destinationOf("how long until we arrive"))
        assertNull(NavVoice.destinationOf("what is playing"))
        assertNull(NavVoice.destinationOf(""))
    }

    /** A lead-in with nothing after it is not a destination. */
    @Test
    fun `an empty destination is refused`() {
        assertNull(NavVoice.destinationOf("take me to"))
        assertNull(NavVoice.destinationOf("navigate to the"))
    }

    @Test
    fun `eta gives a duration and an arrival time`() {
        val reply = NavVoice.compose(NavVoice.Ask.ETA, guiding(progress()))
        assertTrue(reply, reply.contains("to go"))
        assertTrue(reply, reply.contains("arriving around"))
    }

    @Test
    fun `distance gives a distance`() {
        val reply = NavVoice.compose(NavVoice.Ask.DISTANCE, guiding(progress()))
        assertTrue(reply, reply.endsWith("to go."))
    }

    @Test
    fun `not navigating is said plainly rather than answered with a zero`() {
        val reply = NavVoice.compose(NavVoice.Ask.ETA, NavUiState(phase = NavPhase.Idle))
        assertTrue(reply, reply.contains("not navigating"))
    }

    /**
     * A route with no position yet is not the same as no route. Saying "you're not navigating"
     * here would be wrong for the second or two before the first fix lands.
     */
    @Test
    fun `a route that has not started moving is distinguished from no route`() {
        val reply = NavVoice.compose(NavVoice.Ask.ETA, guiding(null))
        assertTrue(reply, reply.contains("just started"))
        assertTrue(reply, !reply.contains("not navigating"))
    }

    @Test
    fun `arrival is announced rather than reported as zero minutes`() {
        val reply = NavVoice.compose(NavVoice.Ask.ETA, guiding(progress(arrived = true)))
        assertEquals("You've arrived.", reply)
    }
}
