package com.motorguard.ivi.data.media.sources

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The broadcast-radio contract — **declared now, implemented when there is a tuner to talk to**.
 *
 * The Pi 5 has no radio hardware, so shipping a fake that pretends to tune would be worse than
 * useless: it would look finished in a demo and mislead whoever picks this up. Instead the shape
 * is fixed here, the UI is written against it, and [UnavailableRadioTuner] is honest about
 * having nothing behind it.
 *
 * To implement against real hardware:
 *  1. `android.hardware.radio.RadioManager` is the AAOS entry point (`RadioManager.Tuner`).
 *     It needs `android.permission.ACCESS_BROADCAST_RADIO`, which is signature|privileged — so
 *     this only works once the app is built into the image under `vendor/motorguard/`.
 *  2. `RadioManager.listModules()` reports which bands the HAL actually supports; feed that into
 *     [supportedBands] rather than assuming FM.
 *  3. `Tuner.step` / `Tuner.seek` / `Tuner.tune` map onto [seek] and [tune]. They are async —
 *     results arrive on `RadioTuner.Callback.onProgramInfoChanged`, which is what [state] should
 *     be built from.
 *  4. RDS arrives in the same callback as `RadioMetadata.METADATA_KEY_RDS_PS` (station name) and
 *     `METADATA_KEY_RDS_RT` (radio text) — those are [RadioStation.rdsName] and [rdsText].
 *  5. Presets are ours to persist; the HAL has no concept of them. DataStore or SharedPreferences
 *     keyed by band + slot is enough.
 */
interface RadioTuner {

    /** Bands the hardware reports. Empty means there is no usable tuner. */
    val supportedBands: Set<RadioBand>

    /** Current tuner state. Hot — reflects seeking and RDS updates as they arrive. */
    fun state(): Flow<RadioState>

    suspend fun setBand(band: RadioBand)

    /** Tune to an exact frequency in kHz. */
    suspend fun tune(frequencyKhz: Int)

    /** Auto-seek to the next station with a signal, per docs/04-media.md. */
    suspend fun seek(forward: Boolean)

    /** Store the currently tuned station into [slot] — the long-press on a preset button. */
    suspend fun savePreset(slot: Int)

    /** Jump to the station in [slot]. No-op when the slot is empty. */
    suspend fun recallPreset(slot: Int)
}

enum class RadioBand { FM, DAB }

data class RadioStation(
    val frequencyKhz: Int,
    val band: RadioBand,
    /** RDS programme service name, e.g. "BBC R4". Null until the broadcast provides one. */
    val rdsName: String? = null,
    /** RDS radio text — the scrolling now-playing line. */
    val rdsText: String? = null,
) {
    /** "104.20 MHz" for FM; DAB is identified by ensemble rather than a dial position. */
    val displayFrequency: String
        get() = when (band) {
            RadioBand.FM -> String.format(java.util.Locale.ROOT, "%.2f MHz", frequencyKhz / 1000.0)
            RadioBand.DAB -> "Channel $frequencyKhz"
        }
}

data class RadioPreset(val slot: Int, val station: RadioStation?)

data class RadioState(
    val band: RadioBand = RadioBand.FM,
    val station: RadioStation? = null,
    val presets: List<RadioPreset> = emptyList(),
    val seeking: Boolean = false,
    val hasSignal: Boolean = false,
)

/**
 * The no-hardware implementation, and the current default.
 *
 * Every method is a deliberate no-op rather than a `TODO()`: the Radio tab is reachable in the
 * UI, and a driver tapping seek on a head unit with no tuner should get the "No signal" empty
 * state, not a crash.
 */
object UnavailableRadioTuner : RadioTuner {
    override val supportedBands: Set<RadioBand> = emptySet()
    override fun state(): Flow<RadioState> = flowOf(RadioState(hasSignal = false))
    override suspend fun setBand(band: RadioBand) = Unit
    override suspend fun tune(frequencyKhz: Int) = Unit
    override suspend fun seek(forward: Boolean) = Unit
    override suspend fun savePreset(slot: Int) = Unit
    override suspend fun recallPreset(slot: Int) = Unit
}
