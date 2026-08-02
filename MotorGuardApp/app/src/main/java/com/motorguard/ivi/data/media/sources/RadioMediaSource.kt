package com.motorguard.ivi.data.media.sources

import com.motorguard.ivi.data.media.MediaLibrarySource
import com.motorguard.ivi.data.media.MediaSourceId
import com.motorguard.ivi.data.media.PlaybackKind
import com.motorguard.ivi.data.media.SourceAvailability
import com.motorguard.ivi.data.media.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Broadcast radio, backed by whichever [RadioTuner] is installed — [UnavailableRadioTuner] today.
 *
 * [tracks] is empty by definition: a tuner has stations, not files. Presets are the closest
 * analogue and they live in [RadioState], which the Radio tab reads directly.
 */
class RadioMediaSource(private val tuner: RadioTuner = UnavailableRadioTuner) : MediaLibrarySource {

    override val id = MediaSourceId.RADIO
    override val label = "Radio"
    override val playbackKind = PlaybackKind.TUNER

    override fun availability(): Flow<SourceAvailability> = tuner.state().map { state ->
        SourceAvailability(
            id = id,
            available = tuner.supportedBands.isNotEmpty() && state.hasSignal,
            emptyMessage = if (tuner.supportedBands.isEmpty()) {
                "No tuner on this hardware"
            } else {
                "No signal"
            },
        )
    }

    override suspend fun tracks(): List<Track> = emptyList()
}
