package com.motorguard.ivi.ui.nav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.data.nav.NavProviders
import com.motorguard.ivi.data.nav.NavRepository
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.VehiclePosition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Owns the Nav tab's state machine and is the only thing that talks to [NavRepository].
 *
 * Everything asynchronous is funnelled through here so the composables stay pure: search is
 * debounced, routing is cancellable, and every failure becomes one nullable [NavUiState.error]
 * string rather than an exception escaping into composition.
 */
class NavViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NavRepository(application)

    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var positionJob: Job? = null

    init {
        collectPositions()
    }

    /**
     * (Re)start the position feed.
     *
     * Restartable because [com.motorguard.ivi.data.nav.oss.AndroidLocationSource] decides
     * whether it can produce anything at *collection* time: if the location permission is
     * granted after the screen opened, or the driver switches to the simulator, the old flow
     * would sit there empty forever.
     */
    private fun collectPositions() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            repository.positions().collect(::onPosition)
        }
    }

    /** Call after the runtime location permission dialog resolves. */
    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) collectPositions()
    }

    /**
     * Escape hatch offered by the "waiting for GPS fix" chip: drive the puck along the route
     * instead of waiting for a receiver. This is the thing that makes the feature demoable at a
     * desk without an emulator and without a rebuild.
     */
    fun useSimulatedLocation() {
        NavConfig.locationMode = NavConfig.LocationMode.SIMULATED
        NavProviders.invalidate()
        collectPositions()
        // The simulator only emits along a route, so re-arm it if one is already active.
        (_state.value.phase as? NavPhase.Guiding)?.let { repository.startGuidance(it.route) }
    }

    // ---------------------------------------------------------------- search

    fun openSearch() {
        _state.update { it.copy(phase = NavPhase.Searching(), error = null) }
    }

    /** Move the caret to the other endpoint field, seeding it with whatever is already there. */
    fun activateField(field: SearchField) {
        val phase = _state.value.phase as? NavPhase.Searching ?: return
        if (phase.active == field) return
        searchJob?.cancel()
        val seed = when (field) {
            SearchField.ORIGIN -> phase.origin?.name.orEmpty()
            SearchField.DESTINATION -> phase.destination?.name.orEmpty()
        }
        _state.update {
            it.copy(
                phase = phase.copy(
                    active = field,
                    query = seed,
                    results = emptyList(),
                    loading = false,
                ),
            )
        }
    }

    /** Set the origin back to "wherever the car is". */
    fun useCurrentLocationAsOrigin() {
        val phase = _state.value.phase as? NavPhase.Searching ?: return
        searchJob?.cancel()
        val next = phase.copy(
            origin = null,
            active = SearchField.DESTINATION,
            query = phase.destination?.name.orEmpty(),
            results = emptyList(),
            loading = false,
        )
        _state.update { it.copy(phase = next) }
        next.destination?.let { requestRoutes(next.origin, it) }
    }

    /**
     * Swap the endpoints. "Your location" has no [Place], so going *into* the destination slot
     * it has to be materialized from the current position — and that snapshot is deliberate:
     * once it is the destination, it should stay where it was, not follow the car.
     */
    fun swapEndpoints() {
        val phase = _state.value.phase as? NavPhase.Searching ?: return
        val newDestination = phase.origin ?: currentLocationPlace()
        val newOrigin = phase.destination
        if (newDestination == null && newOrigin == null) return

        searchJob?.cancel()
        val next = phase.copy(
            origin = newOrigin,
            destination = newDestination,
            query = when (phase.active) {
                SearchField.ORIGIN -> newOrigin?.name.orEmpty()
                SearchField.DESTINATION -> newDestination?.name.orEmpty()
            },
            results = emptyList(),
            loading = false,
        )
        _state.update { it.copy(phase = next) }
        if (newDestination != null) requestRoutes(newOrigin, newDestination)
    }

    /** The car's position as a fixed [Place], or null before the first fix. */
    private fun currentLocationPlace(): Place? = _state.value.position?.let {
        Place(name = "Your location", subtitle = "", point = it.point)
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(phase = NavPhase.Idle) }
    }

    fun onQueryChange(query: String) {
        val phase = _state.value.phase as? NavPhase.Searching ?: return
        _state.update { it.copy(phase = phase.copy(query = query, loading = query.length >= 2)) }

        // Debounce: Photon is a public fair-use instance and a request per keystroke is both
        // rude and slower than waiting for the driver to stop typing.
        searchJob?.cancel()
        if (query.length < 2) {
            _state.update { current ->
                val searching = current.phase as? NavPhase.Searching ?: return@update current
                current.copy(phase = searching.copy(results = emptyList(), loading = false))
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // Bias to the endpoint the driver is *not* editing when it is known — looking for
            // "parking" while setting a destination in another city should find it there, not
            // next to the car.
            val near = when (phase.active) {
                SearchField.ORIGIN -> phase.destination?.point
                SearchField.DESTINATION -> phase.origin?.point
            } ?: _state.value.position?.point ?: NavConfig.defaultOrigin
            val results = runCatching { repository.search(query, near) }
            _state.update { current ->
                val searching = current.phase as? NavPhase.Searching ?: return@update current
                if (searching.query != query) return@update current
                results.fold(
                    onSuccess = { places ->
                        current.copy(
                            phase = searching.copy(results = places, loading = false),
                            error = null,
                        )
                    },
                    onFailure = { failure ->
                        current.copy(
                            phase = searching.copy(results = emptyList(), loading = false),
                            error = failure.userMessage("Search unavailable"),
                        )
                    },
                )
            }
        }
    }

    // ---------------------------------------------------------------- routing

    /**
     * Commit a search result to whichever field is being edited.
     *
     * Picking an origin does not route yet — it advances to the destination field, which is the
     * order a driver fills these in. Picking a destination always routes, because by then both
     * ends are known (a null origin just means "from the car").
     */
    fun pickResult(place: Place) {
        val phase = _state.value.phase as? NavPhase.Searching ?: return
        searchJob?.cancel()

        val next = when (phase.active) {
            SearchField.ORIGIN -> phase.copy(
                origin = place,
                active = if (phase.destination == null) SearchField.DESTINATION else phase.active,
                query = if (phase.destination == null) "" else place.name,
                results = emptyList(),
                loading = false,
            )

            SearchField.DESTINATION -> phase.copy(
                destination = place,
                query = place.name,
                results = emptyList(),
                loading = false,
            )
        }
        _state.update { it.copy(phase = next) }

        // Route as soon as both ends are known — including when the driver went *back* to change
        // the start on a trip that already had a destination.
        next.destination?.let { requestRoutes(next.origin, it) }
    }

    private fun requestRoutes(origin: Place?, destination: Place) {
        routeJob?.cancel()
        _state.update { it.copy(routing = true, error = null) }

        routeJob = viewModelScope.launch {
            val from = origin?.point
                ?: _state.value.position?.point
                ?: NavConfig.defaultOrigin
            runCatching { repository.routes(from, destination) }.fold(
                onSuccess = { routes ->
                    _state.update { current ->
                        if (routes.isEmpty()) {
                            current.copy(routing = false, error = "No route to ${destination.name}")
                        } else {
                            current.copy(
                                phase = NavPhase.Preview(
                                    origin = origin,
                                    destination = destination,
                                    routes = routes,
                                ),
                                routing = false,
                                error = null,
                            )
                        }
                    }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(routing = false, error = failure.userMessage("Could not build a route"))
                    }
                },
            )
        }
    }

    /** Back to the search panel from the preview, with both endpoints intact. */
    fun editRoute() {
        val phase = _state.value.phase as? NavPhase.Preview ?: return
        routeJob?.cancel()
        _state.update {
            it.copy(
                phase = NavPhase.Searching(
                    origin = phase.origin,
                    destination = phase.destination,
                    active = SearchField.DESTINATION,
                    query = phase.destination.name,
                ),
                routing = false,
            )
        }
    }

    fun selectRoute(index: Int) {
        val phase = _state.value.phase as? NavPhase.Preview ?: return
        if (index !in phase.routes.indices) return
        _state.update { it.copy(phase = phase.copy(selectedIndex = index)) }
    }

    fun cancelPreview() {
        routeJob?.cancel()
        _state.update { it.copy(phase = NavPhase.Idle, routing = false) }
    }

    // ---------------------------------------------------------------- guidance

    fun startGuidance() {
        val phase = _state.value.phase as? NavPhase.Preview ?: return
        val route = phase.selected
        repository.startGuidance(route)
        _state.update { it.copy(phase = NavPhase.Guiding(route = route), error = null) }
    }

    fun endGuidance() {
        repository.stopGuidance()
        _state.update { it.copy(phase = NavPhase.Idle) }
    }

    fun toggleMute() {
        val phase = _state.value.phase as? NavPhase.Guiding ?: return
        // Voice guidance itself belongs to the assistant overlay (owner D); this flag is the
        // hand-off point, and muting is honoured the moment that lands.
        _state.update { it.copy(phase = phase.copy(muted = !phase.muted)) }
    }

    fun setFollowing(following: Boolean) {
        val phase = _state.value.phase as? NavPhase.Guiding ?: return
        _state.update { it.copy(phase = phase.copy(following = following)) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ---------------------------------------------------------------- internal

    private fun onPosition(position: VehiclePosition) {
        // Computed once: snapping scans the whole polyline, and this runs at 10 Hz.
        val progress = if (_state.value.phase is NavPhase.Guiding) {
            repository.progressFor(position)
        } else {
            null
        }

        _state.update { current ->
            val phase = current.phase
            if (phase !is NavPhase.Guiding) {
                current.copy(position = position)
            } else {
                current.copy(position = position, phase = phase.copy(progress = progress))
            }
        }

        // Arriving ends the trip on its own — leaving a "0 m, turn right" card up after the car
        // has stopped is the classic way this screen goes stale.
        if (progress?.arrived == true) endGuidance()
    }

    /**
     * Say something actionable. Only genuine connectivity failures get "no connection" — a
     * misconfigured endpoint is also an [IOException], and reporting that as a network problem
     * sends you looking at the Wi-Fi instead of at [NavConfig].
     */
    private fun Throwable.userMessage(fallback: String): String = when (this) {
        is UnknownHostException, is SocketTimeoutException, is ConnectException ->
            "$fallback — no connection"

        else -> message?.takeIf { it.isNotBlank() }?.let { "$fallback: $it" } ?: fallback
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopGuidance()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 280L
    }
}
