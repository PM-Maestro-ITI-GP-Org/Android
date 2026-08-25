package com.motorguard.ivi.ui.voice

import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.RemainingLife
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences the assistant says about the motor, and the questions it agrees to answer.
 *
 * Pure JVM: [MotorVoice.compose] takes the signal and the clock as parameters precisely so the
 * wording can be pinned without a device and without the SOME/IP link existing. What is being
 * tested is not the transport — it is that a fault the unit classified survives the trip to the
 * driver's ear with its type, its severity and its age intact.
 */
class MotorVoiceTest {

    private val now = 1_700_000_000_000L

    private fun motor(
        fault: MotorFaultType = MotorFaultType.NORMAL,
        severity: Severity = Severity.OK,
        life: RemainingLife? = null,
    ) = MotorTelemetry(faultType = fault, faultSeverity = severity, remainingLife = life)

    private fun live(t: MotorTelemetry) = SignalState.Live(t, now)

    private fun say(utterance: String, state: SignalState<MotorTelemetry>) =
        MotorVoice.compose(utterance, state, now)

    // --- which questions are ours ------------------------------------------

    @Test
    fun `claims motor questions`() {
        listOf(
            "is there a fault in the motor",
            "what's wrong with the motor",
            "is the motor okay",
            "is the motor fault electrical or mechanical",
            "how serious is the engine fault",
            "how long has the motor got left",
            "what's the remaining useful life",
            // The phrasings the keyword floor used to miss, leaving them to an embedding model
            // that is not on every image.
            "how is the motor doing",
            "is the engine running okay",
            "does the motor sound right",
            "what is the diagnostics unit saying",
        ).forEach { assertTrue(it, MotorVoice.claims(it)) }
    }

    @Test
    fun `leaves everything else alone`() {
        listOf(
            "play some music",
            "take me home",
            "call mona",
            "what is my tyre pressure",
            "how is the car doing",
            "how much charge is left",
            "",
        ).forEach { assertFalse(it, MotorVoice.claims(it)) }
    }

    /**
     * The one collision worth a test of its own. "Check engine light" contains "engine", and the
     * C++ core has a catalogue entry that explains it properly — this would only talk about a
     * BLDC motor nobody asked about.
     */
    @Test
    fun `warning light questions belong to the core`() {
        assertFalse(MotorVoice.claims("explain the check engine light"))
        assertFalse(MotorVoice.claims("what is that engine warning light"))
    }

    // --- the cluster's codes -------------------------------------------------

    private fun cluster(u: String) = MotorVoice.clusterQueryOf(u)

    @Test
    fun `a spoken code is recognised however it is said`() {
        listOf("what does E-31 mean", "what is e31", "whats e 31", "explain E-21")
            .forEach { assertTrue(it, cluster(it) is MotorVoice.ClusterQuery.Explicit) }
    }

    @Test
    fun `asking about the code on the dash is recognised without one being said`() {
        listOf("what is that code on the cluster", "what's the error code", "what fault code is showing")
            .forEach { assertEquals(it, MotorVoice.ClusterQuery.Current, cluster(it)) }
    }

    /** "e" plus digits is a short pattern, and the handlers after this one own those phrases. */
    @Test
    fun `destinations and contacts are never read as codes`() {
        listOf("take me to route 31", "navigate to gate 21", "call 0100 224 8871", "")
            .forEach { assertNull(it, cluster(it)) }
    }

    /**
     * The phrasing that started this: "what's this error on my cluster" required the literal word
     * "code" next to "cluster", matched nothing, and fell through to a general status report that
     * said there was no fault while a code was on screen.
     */
    @Test
    fun `asking about an error on the cluster is a cluster question`() {
        listOf(
            "what is this error on my cluster",
            "whats this error on the cluster",
            "what is that warning on the dash",
            "what is this light on my dashboard",
            "what is the fault on the display",
            "whats this error",
        ).forEach { assertEquals(it, MotorVoice.ClusterQuery.Current, cluster(it)) }
    }

    /**
     * The cluster is a separate app reading the AI board over its own link, and it is the
     * authority on what it is showing. This side seeing nothing is at least as likely to mean the
     * link is down as it is to mean the fault cleared — so it must not tell a driver looking at a
     * code that there is no code.
     */
    @Test
    fun `no fault on this side never contradicts the cluster`() {
        val current = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Current, live(motor()))
        assertFalse(current, current.contains("shouldn't be showing"))
        assertTrue(current, current.contains("not seeing a fault on my side"))
        // Still answers the question it was asked, rather than only reporting a problem.
        assertTrue(current, current.contains("E-31") && current.contains("E-21"))

        val explicit = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Explicit("E-31"), live(motor()))
        assertTrue(explicit, explicit.contains("mechanical"))
        assertTrue(explicit, explicit.contains("link to the diagnostics unit isn't up"))
        assertFalse(explicit, explicit.contains("should have cleared"))
    }

    /** The mapping the cluster's Main.qml does: 2x electrical, 3x mechanical, E-01 unplaced. */
    @Test
    fun `codes mirror the cluster's own families`() {
        assertEquals("E-21", MotorVoice.clusterCode(MotorFaultType.ELECTRICAL))
        assertEquals("E-31", MotorVoice.clusterCode(MotorFaultType.MECHANICAL))
        assertEquals("E-01", MotorVoice.clusterCode(MotorFaultType.SENSOR))
        assertNull(MotorVoice.clusterCode(MotorFaultType.NORMAL))
    }

    @Test
    fun `each code is explained by its family`() {
        val e21 = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Explicit("E-21"), live(motor()))
        assertTrue(e21, e21.contains("electrical"))
        val e31 = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Explicit("E-31"), live(motor()))
        assertTrue(e31, e31.contains("mechanical"))
        val e01 = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Explicit("E-01"), live(motor()))
        assertTrue(e01, e01.contains("couldn't place"))
    }

    /**
     * Three codes exist. Guessing at the meaning of a fourth is the one answer worth refusing
     * outright — a confident sentence about an unknown fault code is worse than no sentence.
     */
    @Test
    fun `an unknown code is refused rather than invented`() {
        val reply = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Explicit("E-77"), live(motor()))
        assertTrue(reply, reply.contains("isn't a code I know"))
        assertTrue(reply, reply.contains("E-21") && reply.contains("E-31") && reply.contains("E-01"))
    }

    /**
     * The cross-check is the point. The cluster and this app read one classification over two
     * links, so the moment worth catching is when they disagree.
     */
    @Test
    fun `an explained code is checked against the live motor`() {
        val agrees = MotorVoice.explainCluster(
            MotorVoice.ClusterQuery.Explicit("E-31"),
            live(motor(MotorFaultType.MECHANICAL, Severity.CAUTION)),
        )
        assertTrue(agrees, agrees.contains("matches what the motor is reporting"))

        val disagrees = MotorVoice.explainCluster(
            MotorVoice.ClusterQuery.Explicit("E-31"),
            live(motor(MotorFaultType.ELECTRICAL, Severity.CAUTION)),
        )
        assertTrue(disagrees, disagrees.contains("reporting E-21 now"))

        val nothingHere = MotorVoice.explainCluster(
            MotorVoice.ClusterQuery.Explicit("E-31"),
            live(motor(MotorFaultType.NORMAL, Severity.OK)),
        )
        assertTrue(nothingHere, nothingHere.contains("either it's cleared, or my link"))
    }

    /** No live signal means no claim about whether the code still stands. */
    @Test
    fun `no cross-check is asserted when the signal is not live`() {
        val reply = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Explicit("E-31"), SignalState.Offline)
        assertTrue(reply, reply.contains("mechanical"))
        assertTrue(reply, reply.contains("can't check it"))
    }

    @Test
    fun `asking what the cluster shows reads it off the live fault`() {
        val reply = MotorVoice.explainCluster(
            MotorVoice.ClusterQuery.Current,
            live(motor(MotorFaultType.MECHANICAL, Severity.CRITICAL)),
        )
        assertTrue(reply, reply.startsWith("E-31"))

        // No fault here is not a statement about the cluster — see the contradiction test above.
        val none = MotorVoice.explainCluster(MotorVoice.ClusterQuery.Current, live(motor()))
        assertTrue(none, none.contains("not seeing a fault on my side"))
    }

    // --- "any faults?" -------------------------------------------------------

    /**
     * The bug this exists for: left to fall through, this question reached the C++ core's
     * ListFaults intent and got "I'm not seeing any faults at the moment, everything looks fine"
     * out of a list nothing has ever filled — while the diagnostics screen showed the fault.
     */
    @Test
    fun `general fault questions are claimed before the core can deny them`() {
        listOf(
            "any faults", "are there any errors", "any problems", "is anything wrong",
            "is everything ok", "what faults do i have",
        ).forEach { assertTrue(it, MotorVoice.asksForAnyFault(it)) }
    }

    @Test
    fun `unrelated questions are not general fault questions`() {
        listOf("play some music", "take me to the airport", "what is playing", "")
            .forEach { assertFalse(it, MotorVoice.asksForAnyFault(it)) }
    }

    @Test
    fun `any faults reports the live fault and names its cluster code`() {
        val reply = MotorVoice.anyFault(live(motor(MotorFaultType.MECHANICAL, Severity.CRITICAL)))
        assertTrue(reply, reply.contains("mechanical fault"))
        assertTrue(reply, reply.contains("E-31"))
    }

    /** "Everything looks fine" must never be said on behalf of a signal that is not arriving. */
    @Test
    fun `any faults does not claim everything is fine when the link is down`() {
        val reply = MotorVoice.anyFault(SignalState.Offline)
        assertTrue(reply, reply.contains("can't reach"))
        assertFalse(reply, reply.contains("E-"))
    }

    @Test
    fun `any faults with no fault says so without inventing a code`() {
        val reply = MotorVoice.anyFault(live(motor()))
        assertTrue(reply, reply.contains("no fault"))
        assertFalse(reply, reply.contains("E-"))
    }

    // --- the answer ---------------------------------------------------------

    @Test
    fun `names an electrical fault and its severity`() {
        val reply = say(
            "is there a fault in the motor",
            live(motor(MotorFaultType.ELECTRICAL, Severity.CRITICAL)),
        )
        assertTrue(reply, reply.contains("electrical fault"))
        assertTrue(reply, reply.contains("critical"))
    }

    @Test
    fun `names a mechanical fault`() {
        val reply = say(
            "what's wrong with the motor",
            live(motor(MotorFaultType.MECHANICAL, Severity.CAUTION)),
        )
        assertTrue(reply, reply.contains("mechanical fault"))
        assertTrue(reply, reply.contains("caution"))
    }

    @Test
    fun `electrical or mechanical is answered with one of the two`() {
        assertTrue(
            say("is it electrical or mechanical", live(motor(MotorFaultType.ELECTRICAL, Severity.CAUTION)))
                .startsWith("It's an electrical fault"),
        )
        assertTrue(
            say("is it electrical or mechanical", live(motor(MotorFaultType.MECHANICAL, Severity.CAUTION)))
                .startsWith("It's a mechanical fault"),
        )
    }

    /**
     * Electrical and mechanical are the only two type words spoken. Anything else the unit sends
     * is still reported, with its severity, as a fault without a name — the one outcome that must
     * not happen is a raised fault becoming silence while the card shows a coloured dot.
     */
    @Test
    fun `a fault outside the two classes is reported without being named`() {
        val summary = say("is there a fault in the motor", live(motor(MotorFaultType.SENSOR, Severity.CRITICAL)))
        assertTrue(summary, summary.contains("flagged a fault"))
        assertTrue(summary, summary.contains("critical"))
        assertFalse(summary, summary.contains("sensor"))

        val type = say("is it electrical or mechanical", live(motor(MotorFaultType.SENSOR, Severity.CAUTION)))
        assertTrue(type, type.startsWith("Neither"))
        assertFalse(type, type.contains("sensor"))
    }

    /** No answer about the motor may invent a third class of fault. */
    @Test
    fun `no answer names a fault class other than electrical or mechanical`() {
        val questions = listOf(
            "is there a fault in the motor", "is it electrical or mechanical",
            "how bad is the motor fault", "how long has the motor got left",
        )
        MotorFaultType.entries.forEach { type ->
            Severity.entries.forEach { sev ->
                questions.forEach { q ->
                    val reply = say(q, live(motor(type, sev))).lowercase()
                    listOf("sensor", "tyre", "tire", "pressure", "battery", "brake").forEach { banned ->
                        assertFalse("$type/$sev \"$q\" -> $reply", reply.contains(banned))
                    }
                }
            }
        }
    }

    @Test
    fun `no fault is a real answer`() {
        val reply = say("is there a fault in the motor", live(motor()))
        assertEquals("The motor diagnostics unit reports no fault.", reply)
    }

    /**
     * NORMAL with a raised severity is the unit contradicting itself. Repeating either half alone
     * would be picking a side; the driver is told both and pointed at the screen.
     */
    @Test
    fun `a severity without a fault type is not silently dropped`() {
        val reply = say("is the motor okay", live(motor(MotorFaultType.NORMAL, Severity.CRITICAL)))
        assertTrue(reply, reply.contains("no fault type"))
        assertTrue(reply, reply.contains("critical alert"))
    }

    // --- remaining life -----------------------------------------------------

    @Test
    fun `remaining life is auto scaled the way the card scales it`() {
        val reply = say("how long has the motor got left", live(motor(life = RemainingLife(2922f))))
        assertTrue(reply, reply.contains("4 months"))
    }

    @Test
    fun `percent is spoken only when the unit sent one`() {
        assertFalse(
            say("how long has the motor got left", live(motor(life = RemainingLife(100f))))
                .contains("%"),
        )
        assertTrue(
            say("how long has the motor got left", live(motor(life = RemainingLife(100f, 42f))))
                .contains("42%"),
        )
    }

    /** Null RUL is "no estimate", never a fabricated number, and never volunteered unasked. */
    @Test
    fun `no estimate is said when asked and left out when not`() {
        assertTrue(
            say("how long has the motor got left", live(motor())).contains("hasn't given an estimate"),
        )
        assertFalse(
            say("is there a fault in the motor", live(motor())).contains("estimate"),
        )
    }

    // --- freshness ----------------------------------------------------------

    @Test
    fun `an unreachable unit is said to be unreachable`() {
        val reply = say("is there a fault in the motor", SignalState.Offline)
        assertTrue(reply, reply.contains("can't reach"))
        assertFalse(reply, reply.contains("fault,"))
    }

    @Test
    fun `nothing received yet is not reported as no fault`() {
        val reply = say("is there a fault in the motor", SignalState.Loading)
        assertTrue(reply, reply.contains("haven't had a reading"))
    }

    /**
     * The reason this is answered here rather than by pushing the fault into the C++ core: the
     * core has no concept of a signal going stale and would report a fifteen-minute-old
     * classification as the current one.
     */
    @Test
    fun `a stale reading is dated rather than passed off as current`() {
        val reply = MotorVoice.compose(
            "is there a fault in the motor",
            SignalState.Stale(motor(MotorFaultType.ELECTRICAL, Severity.CRITICAL), now - 300_000L),
            now,
        )
        assertTrue(reply, reply.contains("stopped reporting"))
        assertTrue(reply, reply.contains("5m ago"))
        assertTrue(reply, reply.contains("electrical fault"))
    }
}
