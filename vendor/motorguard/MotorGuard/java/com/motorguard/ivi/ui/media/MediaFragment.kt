// MediaFragment — owner B
// Multi-source playback with a segmented source switcher.
package com.motorguard.ivi.ui.media

import androidx.fragment.app.Fragment

/**
 * FEATURES
 *  - Source tabs: USB · BLUETOOTH · RADIO
 *  - USB     : browse folders/artists/albums, queue, search  (MediaStore scan)
 *  - BLUETOOTH: phone track metadata + transport             (A2DP + AVRCP)
 *  - RADIO   : FM/DAB band toggle, seek/tune, presets, RDS    (RadioManager/tuner)
 *  - Unified transport bar + now-playing across all sources
 *  - Playlist rows (PlaylistRow component), current-track equalizer
 * READS   : MediaSourceManager (usb/bt/radio), MediaSession metadata
 * WRITES  : transport controls, active source, radio presets
 */
class MediaFragment : Fragment()
