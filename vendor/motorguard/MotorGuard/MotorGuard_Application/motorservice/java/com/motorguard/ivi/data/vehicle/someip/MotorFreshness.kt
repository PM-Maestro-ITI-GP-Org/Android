package com.motorguard.ivi.data.vehicle.someip

import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.SignalState

/**
 * How long silence is tolerated before the screen stops claiming the motor is fine.
 *
 * The unit publishes at 1 Hz (docs/10 §3.1), so three missed cycles is the point at which
 * something is wrong rather than late, and fifteen is the point at which the last value is no
 * longer worth showing at all. Both are in docs/09 §4.1 and both ends were written to them.
 *
 * Pure and clock-injected: every transition here is a rule the UI's honesty depends on, and all
 * of them are reachable in a test with a fake clock instead of a fifteen-second wait.
 */
internal class MotorFreshness(
    private val staleAfterMs: Long = 3_000,
    private val offlineAfterMs: Long = 15_000,
) {
    private var last: MotorTelemetry? = null
    private var lastAtMs: Long = 0
    private var seenAnything = false
    private var linkDown = false

    /** A message arrived. [atMs] is local receive time — see [state] for why not the sender's. */
    fun onEvent(data: MotorTelemetry, atMs: Long) {
        last = data
        lastAtMs = atMs
        seenAnything = true
        linkDown = false
    }

    /**
     * The transport itself went away: the peer withdrew its offer, or discovery lost it.
     *
     * This is not the same as messages stopping, and it does not wait fifteen seconds. A source
     * we know is unreachable has nothing to be stale about.
     */
    fun onLinkDown() {
        linkDown = true
    }

    /**
     * The transport found the peer again. This does not by itself make anything live — the last
     * value is still however old it is, and only a message can change that — it just stops the
     * link being the reason for [SignalState.Offline].
     */
    fun onLinkUp() {
        linkDown = false
    }

    /**
     * The state to publish at [nowMs].
     *
     * `timestampMs` is **local receive time**, not the sender's clock. docs/10 §3.5 defines that
     * clock as monotonic-since-boot on the diagnostics unit, and no boot epoch is exchanged, so
     * converting it would mean inventing an offset. docs/09 §4.3 permits this fallback and asks
     * that it be stated: the consequence is that the stale badge's age includes network delay,
     * which at 1 Hz over a local link is not visible, and that it would drift if the two devices
     * were ever far apart. Publishing a boot epoch from the unit is what would fix it properly.
     */
    fun state(nowMs: Long): SignalState<MotorTelemetry> {
        val data = last
        if (linkDown) return SignalState.Offline
        if (!seenAnything || data == null) return SignalState.Loading

        val age = nowMs - lastAtMs
        return when {
            age >= offlineAfterMs -> SignalState.Offline
            age >= staleAfterMs -> SignalState.Stale(data, lastAtMs)
            else -> SignalState.Live(data, lastAtMs)
        }
    }
}
