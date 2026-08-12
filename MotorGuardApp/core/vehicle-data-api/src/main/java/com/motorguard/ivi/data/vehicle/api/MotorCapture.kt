package com.motorguard.ivi.data.vehicle.api

/**
 * One requested window of the motor's raw signals, at [SAMPLE_RATE_HZ].
 *
 * Stored as parallel [FloatArray]s, one per channel, rather than a list of sample objects. A
 * ten-second capture is 200,000 samples across twelve channels; as objects that is 2.4 million
 * allocations and enough GC pressure to visibly stutter the plot it exists to draw.
 *
 * There is no timestamp array. Samples are evenly spaced by construction, so the time of index `i`
 * is `i / SAMPLE_RATE_HZ` — an array would be 800 KB spent restating that.
 */
data class MotorCapture(
    val capturedAtMs: Long,
    val speedVoltCmd: FloatArray,
    val current: Array<FloatArray>,
    val voltage: Array<FloatArray>,
    val dcBusVolts: FloatArray,
    val vibration: Array<FloatArray>,
    val rpm: FloatArray,
) {
    val sampleCount: Int get() = speedVoltCmd.size

    val durationSec: Float get() = sampleCount / SAMPLE_RATE_HZ

    /** The channels behind one [MotorSignalGroup], in the order they should be drawn. */
    fun channelsOf(group: MotorSignalGroup): List<FloatArray> = when (group) {
        MotorSignalGroup.SPEED_COMMAND -> listOf(speedVoltCmd)
        MotorSignalGroup.CURRENT -> current.toList()
        MotorSignalGroup.VOLTAGE -> voltage.toList()
        MotorSignalGroup.VIBRATION -> vibration.toList()
        MotorSignalGroup.DC_BUS -> listOf(dcBusVolts)
        MotorSignalGroup.SPEED_ACTUAL -> listOf(rpm)
    }

    // Arrays give data classes reference equality, which would make two captures with identical
    // samples compare unequal and, worse, make `hashCode` unstable across copies. Neither is used
    // for anything here, so both are defined honestly rather than left surprising.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** Fixed by the acquisition hardware. */
        const val SAMPLE_RATE_HZ = 20_000f
    }
}

/**
 * What the plot can show, as a closed set. Grouped rather than listed per channel because the three
 * phases of a current are only meaningful drawn over each other — a single phase in isolation says
 * nothing about the imbalance that identifies an electrical fault.
 */
enum class MotorSignalGroup(
    val label: String,
    val unit: String,
    /**
     * How much time this group is worth showing at once.
     *
     * The speed command is a slow envelope and wants the whole capture; a phase current oscillates
     * at hundreds of hertz and becomes a solid band of ink past about a tenth of a second. These
     * are the numbers to retune once there is real hardware to look at.
     */
    val windowSec: Float,
    /**
     * The vertical scale this signal is always drawn against — the instrument's range, not the
     * data's.
     *
     * Fixed rather than fitted to whatever is on screen, because a scale that rescales itself makes
     * every window look the same: a healthy motor and a stalled one both fill the plot, and
     * scrubbing through a run appears to change nothing while the axis silently moves underneath.
     * With a constant range the height of a trace means something on its own, and two windows can
     * be compared by eye.
     *
     * Chosen to hold a healthy motor with headroom for a faulty one. A value outside the range
     * draws along the edge rather than vanishing, which reads as off-scale the way an instrument
     * pinned against its stop does.
     */
    val displayRange: ClosedFloatingPointRange<Float>,
    /**
     * Whether this signal opens at [windowSec] or at the whole capture.
     *
     * Per signal because the interesting scale is a property of the signal, not a preference. A
     * phase current is a solid band of ink unless you are inside a tenth of a second; the two
     * speeds are envelopes whose whole point is the shape of the run, and opening them zoomed in
     * shows a nearly flat line that says nothing. Every signal remains zoomable both ways — this
     * only decides where it starts.
     */
    val opensZoomedIn: Boolean,
) {
    // Ranges are the instrument's, sized for a 48 V / 450 W / 750 rpm BLDC with headroom for a
    // fault: 450 W at 48 V is about 9.4 A of bus current, so a phase peak of 16 A is generous;
    // phase voltage cannot exceed the bus.
    SPEED_COMMAND("Speed cmd", "V", 0.5f, 0f..5f, opensZoomedIn = false),
    CURRENT("Current x3", "A", 0.04f, -16f..16f, opensZoomedIn = true),
    VOLTAGE("Voltage x3", "V", 0.04f, -30f..30f, opensZoomedIn = true),
    VIBRATION("Vibration xyz", "g", 0.5f, -2f..2f, opensZoomedIn = true),
    DC_BUS("DC bus", "V", 2f, 40f..52f, opensZoomedIn = true),
    SPEED_ACTUAL("Speed", "rpm", 0.5f, 0f..800f, opensZoomedIn = false),
}

/** Where a capture request has got to. */
sealed interface CaptureState {
    /** No capture requested yet this session. */
    data object Idle : CaptureState

    /**
     * A request is in flight. Acquisition takes real time at 20 kHz, so this is a state the user
     * will see rather than a formality.
     */
    data object Requesting : CaptureState

    data class Ready(val capture: MotorCapture) : CaptureState

    /**
     * The request failed. Carrying a reason rather than a bare flag because "the diagnostics unit
     * did not answer" and "it answered with nothing" are different problems, and a spinner that
     * never resolves is the worst possible rendering of either.
     */
    data class Failed(val message: String) : CaptureState
}

/**
 * Requests raw capture windows from the diagnostics unit.
 *
 * Separate from [VehicleDataSource] because the shape is different: that is a set of streams the
 * vehicle pushes, this is a request that takes time and can fail. Folding a suspending, failable
 * call into a flow of telemetry would make every consumer of the telemetry handle a failure mode
 * that has nothing to do with them.
 */
interface MotorCaptureSource {
    /** Suspends for as long as acquisition takes. Implementations must not throw; a failure is a
     *  [CaptureState.Failed] so callers cannot forget to render it. */
    suspend fun requestCapture(): CaptureState
}
