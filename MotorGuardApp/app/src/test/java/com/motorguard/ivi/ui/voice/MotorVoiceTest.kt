package com.motorguard.ivi.ui.voice

import com.motorguard.ivi.data.vehicle.api.MotorFaultType
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.RemainingLife
import com.motorguard.ivi.data.vehicle.api.Severity
import com.motorguard.ivi.data.vehicle.api.SignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
