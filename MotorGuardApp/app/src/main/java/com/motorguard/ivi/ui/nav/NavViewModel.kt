package com.motorguard.ivi.ui.nav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motorguard.ivi.data.nav.NavConfig
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
import java.io.IOException

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

    init {
        // One long-lived collection for the whole tab. The simulator emits only while a route
        // is set, so this idles at zero cost until guidance starts.
        viewModelScope.launch {
            repository.positions().collect(::onPosition)
        }
    }

    // ---------------------------------------------------------------- search

    fun openSearch() {
        _state.update { it.copy(phase = NavPhase.Searching(), error = null) }
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
            val results = runCatching {
                repository.search(query, _state.value.position?.point ?: NavConfig.defaultOrigin)
            }
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

    fun pickDestination(place: Place) {
        searchJob?.cancel()
        routeJob?.cancel()
        _state.update { it.copy(routing = true, error = null) }

        routeJob = viewModelScope.launch {
            val origin = _state.value.position?.point ?: NavConfig.defaultOrigin
            runCatching { repository.routes(origin, place) }.fold(
                onSuccess = { routes ->
                    _state.update { current ->
                        if (routes.isEmpty()) {
                            current.copy(routing = false, error = "No route to ${place.name}")
                        } else {
                            current.copy(
                                phase = NavPhase.Preview(destination = place, routes = routes),
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

    /** Network failures are the common case here; say something a driver can act on. */
    private fun Throwable.userMessage(fallback: String): String = when (this) {
        is IOException -> "$fallback — no connection"
        else -> message?.takeIf { it.isNotBlank() }?.let { "$fallback ($it)" } ?: fallback
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopGuidance()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 280L
    }
}
