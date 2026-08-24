package com.motorguard.ivi.ui.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one thing [com.motorguard.ivi.ui.components.NavRail] and [VoiceOverlaySession] need to
 * agree on despite living in two different Android windows — the rail is a `ComposeView` inside
 * `MainActivity`'s window, the overlay is a real `VoiceInteractionSession` window of its own.
 * Same process, so a plain singleton works; Compose has no API that animates an element across
 * two independent windows, which is why the flight is a same-window "ghost" that starts at
 * [dockedScreenPosition] rather than a literal shared element.
 */
object VoiceOverlayState {
    /** True for the lifetime of the overlay window, set from [VoiceOverlaySession.onShow]/[VoiceOverlaySession.onHide]. */
    val isOpen = MutableStateFlow(false)

    /** The docked mascot's last known position in absolute screen coordinates, updated on every
     *  layout pass. Null until the rail has composed at least once. */
    var dockedScreenPosition by mutableStateOf<Offset?>(null)
}
