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

    /** The channels behind one [MotorSignalGroup], in the order they should be drawn. Raw counts. */
    fun channelsOf(group: MotorSignalGroup): List<FloatArray> = when (group) {
        MotorSignalGroup.SPEED_COMMAND -> listOf(speedVoltCmd)
        MotorSignalGroup.CURRENT -> current.toList()
        MotorSignalGroup.VOLTAGE -> voltage.toList()
        MotorSignalGroup.VIBRATION -> vibration.toList()
        MotorSignalGroup.DC_BUS -> listOf(dcBusVolts)
        MotorSignalGroup.SPEED_ACTUAL -> listOf(rpm)
    }

    /** SI-scaled channels for display — the plot's `displayRange` is in SI (A, V, g), not raw ADC counts.
     *  Raw capture arrives as 12-bit ADC counts (0..4095) per docs/10 §5.3; the fixed `displayRange`
     *  was sized for SI, so drawing raw would pin every trace off-scale. Scaling here keeps the
     *  capture itself raw (so `summarise()` and its BlockAnalyzer can stay count-based) while the
     *  waveform sees calibrated units. Constants mirror `motor_ai_node/config/feature_extraction.json`
     *  `adc` block and `qt-cluster/MotorBlockAnalyzer.h` bench capture (728800 rows).
     *  Heuristic: fake synthesis (used in Gradle) already emits SI (e.g., 11A), so scaling is skipped
     *  when the sample magnitude is already within SI range. */
    fun scaledChannelsOf(group: MotorSignalGroup): List<FloatArray> {
        fun isRaw(maxAbs: Float, threshold: Float) = maxAbs > threshold
        return when (group) {
            MotorSignalGroup.SPEED_COMMAND -> {
                val rawMax = speedVoltCmd.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
                if (isRaw(rawMax, 10f)) listOf(FloatArray(speedVoltCmd.size) { i -> speedVoltCmd[i] * VOLTS_PER_COUNT_SPEED })
                else listOf(speedVoltCmd)
            }
            MotorSignalGroup.CURRENT -> current.map { ch ->
                val rawMax = ch.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
                if (isRaw(rawMax, 50f)) FloatArray(ch.size) { i -> (ch[i] - ADC_MIDSCALE) * AMPS_PER_COUNT } else ch
            }
            MotorSignalGroup.VOLTAGE -> voltage.map { ch ->
                val rawMax = ch.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
                if (isRaw(rawMax, 80f)) FloatArray(ch.size) { i -> ch[i] * VOLTS_PER_COUNT_PHASE } else ch
            }
            MotorSignalGroup.VIBRATION -> vibration.map { ch ->
                val rawMax = ch.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
                if (isRaw(rawMax, 10f)) FloatArray(ch.size) { i -> ch[i] / IMU_COUNTS_PER_G } else ch
            }
            MotorSignalGroup.DC_BUS -> {
                val rawMax = dcBusVolts.maxOrNull()?.let { kotlin.math.abs(it) } ?: 0f
                if (isRaw(rawMax, 80f)) listOf(FloatArray(dcBusVolts.size) { i -> dcBusVolts[i] * VOLTS_PER_COUNT_BUS }) else listOf(dcBusVolts)
            }
            MotorSignalGroup.SPEED_ACTUAL -> listOf(rpm) // no tach fitted, always 0 — kept raw
        }
    }

    // Arrays give data classes reference equality, which would make two captures with identical
    // samples compare unequal and, worse, make `hashCode` unstable across copies. Neither is used
    // for anything here, so both are defined honestly rather than left surprising.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** Fixed by the acquisition hardware. */
        const val SAMPLE_RATE_HZ = 20_000f

        // ADC / sensor calibration — mirrors motor_ai_node/config/feature_extraction.json `adc`
        // and qt-cluster bench capture (MotorBlockAnalyzer.h, 728800 rows). Single source for
        // display scaling and for `summarise()` SI corrections.
        const val ADC_MAX = 4095f
        const val VREF = 3.3f
        const val ADC_MIDSCALE = 2047f
        const val SHUNT_R = 0.0015f
        const val CSA_GAIN = 50f
        const val AMPS_PER_COUNT = VREF / (ADC_MAX * SHUNT_R * CSA_GAIN) // 0.010695, physics-derived (matches max_current 22A)
        const val VBUS_VOLTS = 48f
        const val PHASE_RAIL_COUNTS = 2071f
        const val BUS_COUNTS = 2633f
        const val VOLTS_PER_COUNT_PHASE = VBUS_VOLTS / PHASE_RAIL_COUNTS // 0.02318
        const val VOLTS_PER_COUNT_BUS = VBUS_VOLTS / BUS_COUNTS // 0.01823
        const val VOLTS_PER_COUNT_SPEED = VREF / ADC_MAX // 0.000805, divider 1.0 (0..3.3V -> 0..5V range); with 60V hint divider 18.18 would be 0..60
        const val IMU_COUNTS_PER_G = 16384f
        // Friend hint: volt_divider_gain 1.0 gives 0..3.3V, should be 0..60V => divider 60/3.3 ≈18.18, volts=raw*60/ADC_MAX=0.01465.
        // Kept as PHASE/BUS above (bench-measured) since those pin bus 48V at 2633 counts; 60/4095 would read bus 38.6V.
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
    // Volt scaling 0..60 hint (volt_divider_gain 1.0->0..3.3 should be 0..60) is covered by
    // VOLTS_PER_COUNT_* bench measures; display 0..60 holds rail 48V with headroom.
    SPEED_COMMAND("Speed cmd", "V", 0.5f, 0f..5f, opensZoomedIn = false),
    CURRENT("Current x3", "A", 0.04f, -16f..16f, opensZoomedIn = true),
    VOLTAGE("Voltage x3", "V", 0.04f, 0f..60f, opensZoomedIn = true),
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
