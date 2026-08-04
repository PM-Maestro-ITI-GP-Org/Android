package com.motorguard.ivi.ui.nav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.motorguard.ivi.data.nav.Place
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin adapter over [NavSession].
 *
 * This used to be the Nav tab's state machine, but scoping it to the fragment meant leaving the
 * tab cleared the ViewModel and restarted the drive (the fragment is `replace`d, so destroyed).
 * The whole machine now lives in the process-lifetime [NavSession]; this class exists only so
 * [NavScreen]'s `viewModel()` default keeps working. Every method just forwards, and [state] is
 * the session's own flow — so leaving and returning to Nav observes the SAME session, not a fresh
 * one.
 *
 * Crucially there is NO `onCleared()` that stops guidance: the point is that destroying this
 * adapter must NOT touch the session. Guidance ends only when the driver ends it (or the car
 * arrives), which is when [NavSession] tears down [NavService].
 */
class NavViewModel(application: Application) : AndroidViewModel(application) {

    init {
        // Whichever touches the session first wires it (the service can also); idempotent.
        NavSession.ensureStarted(application)
    }

    val state: StateFlow<NavUiState> get() = NavSession.state

    fun onLocationPermissionResult(granted: Boolean) = NavSession.onLocationPermissionResult(granted)
    fun useSimulatedLocation() = NavSession.useSimulatedLocation()

    fun openSearch() = NavSession.openSearch()
    fun activateField(field: SearchField) = NavSession.activateField(field)
    fun useCurrentLocationAsOrigin() = NavSession.useCurrentLocationAsOrigin()
    fun swapEndpoints() = NavSession.swapEndpoints()
    fun closeSearch() = NavSession.closeSearch()
    fun onQueryChange(query: String) = NavSession.onQueryChange(query)

    fun pickResult(place: Place) = NavSession.pickResult(place)
    fun editRoute() = NavSession.editRoute()
    fun selectRoute(index: Int) = NavSession.selectRoute(index)
    fun cancelPreview() = NavSession.cancelPreview()

    fun startGuidance() = NavSession.startGuidance()
    fun endGuidance() = NavSession.endGuidance()
    fun toggleMute() = NavSession.toggleMute()
    fun setFollowing(following: Boolean) = NavSession.setFollowing(following)
    fun dismissError() = NavSession.dismissError()
}
