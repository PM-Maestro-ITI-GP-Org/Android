package com.motorguard.ivi.data.media

import android.content.Context
import com.motorguard.ivi.data.nav.NavProviders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Is the car moving? The one question the video player has to ask before it shows anything.
 *
 * Watching video in a moving car is the distraction rule every automotive UI guideline agrees
 * on, and docs/00-skeleton.md already commits this project to honouring `CarUxRestrictions`.
 * There is no car-lib on this build to ask, so the speed comes from the same
 * [com.motorguard.ivi.data.nav.NavServices.LocationSource] the navigation tab reads — today the
 * route simulator, and the real GNSS receiver the moment `NavConfig.locationMode` is switched.
 * That is the honest signal available; when car-lib does land, this object is the only place
 * that changes.
 *
 * It fails **safe by default in the direction of usability**: if there is no location source at
 * all the car reads as stationary, because a head unit parked in a workshop with no GNSS fix
 * should not refuse to play a video. Once a source is reporting, its speed is believed.
 */
object DrivingState {

    /**
     * Above this, video is blocked.
     *
     * Walking pace rather than zero: GNSS speed jitters by a few km/h even at a standstill, and a
     * threshold of zero would flicker the player off and on while parked.
     */
    private const val MOVING_KPH = 5

    fun isMoving(context: Context): Flow<Boolean> =
        NavProviders.of(context).location
            .positions()
            .map { it.speedKph > MOVING_KPH }
            .distinctUntilChanged()
}
