package com.motorguard.ivi.ui.nav

import android.content.Context
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.data.nav.NavProviders
import com.motorguard.ivi.data.nav.NavRepository
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.PlaceCategory
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.data.nav.RouteMath
import com.motorguard.ivi.data.nav.VehiclePosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The Nav tab's whole state machine, hoisted OUT of the fragment-scoped [NavViewModel] and into a
 * process-lifetime singleton so it outlives the fragment.
 *
 * The problem this solves: [MainActivity][com.motorguard.ivi.MainActivity] swaps tabs with
 * `replace(...)`, which DESTROYS the leaving fragment. When the state lived in an
 * `AndroidViewModel` it was scoped to that fragment, so leaving Nav cleared the ViewModel and the
 * drive restarted from zero on return. Here the session — repository, jobs, and [state] — hangs
 * off an app-scoped [CoroutineScope] instead of a `viewModelScope`, so search, routing and the
 * 10 Hz position feed keep running while the fragment is gone. [NavViewModel] is now a thin
 * adapter (see that file) and every entry/return observes this same object.
 *
 * [NavService] is the other half: while guidance is active it holds a foreground notification so
 * the process (and therefore this session) also survives the app being backgrounded, not just a
 * tab switch. This object starts/stops that service at the guidance boundaries.
 *
 * The logic below is a verbatim relocation of what used to be in [NavViewModel] — same debounce,
 * same cancellable routing, same snap-to-route progress. Only the scope and the service hooks are
 * new.
 */
/**
 * What came of a spoken "take me to ...". Distinct cases rather than a nullable route, because the
 * driver needs a different sentence for each and "I couldn't do that" covers over the difference
 * between a place that does not exist and a search server that is down.
 */
sealed interface SpokenNavResult {
    data class Started(val destination: Place, val route: Route) : SpokenNavResult
    data class NoResults(val query: String) : SpokenNavResult
    data class NoRoute(val destination: Place) : SpokenNavResult
    data class Failed(val message: String) : SpokenNavResult
    data object NotReady : SpokenNavResult

    /**
     * No position fix, so "nearest" has no meaning.
     *
     * Distinct from every other failure because it is the one where an answer *could* be
     * produced and must not be: [NavConfig.defaultOrigin] is a real coordinate and searching
     * around it would return real, confidently-named places near a building in Smart Village
     * that the car may be nowhere near. Wrong and plausible is the worst pair.
     */
    data object NoFix : SpokenNavResult
}

object NavSession {

    /**
     * App/service-scoped — NOT a `viewModelScope`. A [SupervisorJob] so one failed child (a search
     * that threw) never tears the whole session down, on [Dispatchers.Main] to match the original
     * ViewModel's threading (StateFlow updated on the main thread, collectors are UI).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set on first [ensureStarted]; the application context that outlives every fragment. */
    private lateinit var appContext: Context
    private lateinit var repository: NavRepository
    private var started = false

    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var positionJob: Job? = null

    /**
     * Idempotent bring-up. The adapter [NavViewModel] and [NavService] both call this so whichever
     * touches the session first wires it, and later calls are no-ops. Only [collectPositions] on
     * the very first call — re-entering the Nav tab must NOT restart the feed and lose the drive.
     */
    fun ensureStarted(context: Context) {
        if (started) return
        appContext = context.applicationContext
        repository = NavRepository(appContext)
        started = true
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
        positionJob = scope.launch {
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
        searchJob = scope.launch {
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

        routeJob = scope.launch {
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

    /**
     * Search, route and start guiding, as one call — the whole flow the search panel walks a
     * driver through, for someone whose hands are on the wheel.
     *
     * Not driven through [onQueryChange] and [pickResult]. Those are shaped for a person typing:
     * the query path is debounced, both are gated on the phase being [NavPhase.Searching], and
     * reaching guidance means synthesising four UI transitions and hoping the debounce settles.
     * This takes the same two repository calls they take and ends at the same [startGuidance], so
     * the phase, the map and the foreground service are exactly where they would be had the
     * driver tapped it themselves.
     *
     * **The first result is taken.** That is the compromise this feature is: the overlay answers
     * one utterance and has no way to ask "did you mean?", so either voice cannot set a
     * destination at all or it picks. What makes it defensible is that the caller speaks the
     * resolved name back — the driver hears where they are being sent before they are sent — and
     * "cancel the route" is one sentence away.
     */
    suspend fun navigateTo(query: String): SpokenNavResult {
        val trimmed = query.trim()
        if (trimmed.length < 2) return SpokenNavResult.NoResults(trimmed)
        if (!started) return SpokenNavResult.NotReady

        // A search panel left open would sit behind the guidance the driver is about to get.
        searchJob?.cancel()
        routeJob?.cancel()

        val near = _state.value.position?.point ?: NavConfig.defaultOrigin
        val places = runCatching { repository.search(trimmed, near) }.getOrElse {
            return SpokenNavResult.Failed(it.userMessage("Search unavailable"))
        }
        val destination = places.firstOrNull() ?: return SpokenNavResult.NoResults(trimmed)

        _state.update { it.copy(routing = true, error = null) }
        val routes = runCatching { repository.routes(near, destination) }.getOrElse {
            _state.update { current -> current.copy(routing = false) }
            return SpokenNavResult.Failed(it.userMessage("Could not build a route"))
        }
        if (routes.isEmpty()) {
            _state.update { it.copy(routing = false) }
            return SpokenNavResult.NoRoute(destination)
        }

        _state.update {
            it.copy(
                // Origin null is "from the car", the same value the panel commits for the common
                // case — a trip that starts wherever the vehicle happens to be.
                phase = NavPhase.Preview(origin = null, destination = destination, routes = routes),
                routing = false,
                error = null,
            )
        }
        startGuidance()
        return SpokenNavResult.Started(destination, routes.first())
    }

    /**
     * Find the closest thing matching [query] and drive there.
     *
     * Three things separate this from [navigateTo], and each is a way the naive version is
     * wrong:
     *
     * **It insists on a real fix.** [navigateTo] falls back to [NavConfig.defaultOrigin] when
     * the car's position is unknown, which is fine for "take me to Cairo Airport" — the airport
     * is where it is regardless. "Nearest" is a claim *about where the car is*, so answering it
     * from a default coordinate would name a genuine petrol station near Smart Village and be
     * confidently, invisibly wrong. [SpokenNavResult.NoFix] instead.
     *
     * **It sorts by distance.** Photon biases toward `near` but orders by relevance, not
     * proximity — it is a geocoder, not a POI ranker. Taking `first()` for a "nearest" question
     * is the bug this method exists to avoid, and it would be invisible: every result really is
     * a petrol station, just not the closest one.
     *
     * **It asks the map, not the text index.** This was the bug that made the feature useless:
     * [NavRepository.search] is a geocoder, and `near` only biases the ranking of a global name
     * match. Asked for "petrol station" it returned places *called* that, hours away, and ranked
     * them above the one down the road — because the road one is not called "Petrol Station", it
     * is called Wataniya and tagged `amenity=fuel`. [NavRepository.nearby] queries by position
     * and OSM tag with a hard radius, so every result is genuinely near, and nothing in range is
     * an answer rather than a reason to reach further out.
     *
     * **It ranks by road distance, not by the crow.** Straight-line proximity is only the
     * shortlist: the nearest station as the crow flies can be across a river, the wrong side of a
     * motorway, or behind a one-way system, and a driver told "nearest" and then routed 6 km
     * around has been given a true number that answered the wrong question. So every shortlisted
     * candidate is actually routed and the shortest **route** wins.
     *
     * The routes are fetched concurrently, which is what makes that affordable: the cost is one
     * round trip in wall-clock time rather than [MAX_NEAREST_CANDIDATES] of them, on a request a
     * driver is waiting through with a spinner up. The shortlist stays small anyway — the routing
     * service is a public fair-use instance, and four parallel requests is the most this should
     * ask of it for one spoken question.
     *
     * Candidates that cannot be routed at all simply drop out, so an unreachable nearest falls
     * through to the next rather than failing the request.
     */
    suspend fun navigateToNearest(query: String, osmTags: List<String>): SpokenNavResult {
        if (!started) return SpokenNavResult.NotReady
        val here = _state.value.position?.point ?: return SpokenNavResult.NoFix

        searchJob?.cancel()
        routeJob?.cancel()

        // Widening rings rather than one big one. The first radius that finds anything wins, so a
        // driver in a city is answered from a few kilometres and never sent to something forty
        // away that merely ranked well; a driver with genuinely nothing close still gets an
        // answer rather than a refusal.
        var found = emptyList<Place>()
        for (radiusKm in NEARBY_RADII_KM) {
            found = runCatching { repository.nearby(osmTags, here, radiusKm) }.getOrElse {
                return SpokenNavResult.Failed(it.userMessage("Search unavailable"))
            }
            if (found.isNotEmpty()) break
        }
        // Nothing in range is a real answer, and it is left as one. Falling back to a text search
        // here is exactly what produced results hours away: it always has something to offer and
        // no notion of far.
        val byDistance = found.sortedBy { RouteMath.distanceMeters(here, it.point) }
        if (byDistance.isEmpty()) return SpokenNavResult.NoResults(query)

        _state.update { it.copy(routing = true, error = null) }
        val shortlist = byDistance.take(MAX_NEAREST_CANDIDATES)
        val routed = coroutineScope {
            shortlist.map { place ->
                async { place to runCatching { repository.routes(here, place) }.getOrNull().orEmpty() }
            }.awaitAll()
        }

        // Per place, its own best route; across places, the shortest of those. Comparing a
        // provider's first alternative against another's would rank the services' preferences
        // rather than the roads.
        val best = routed
            .mapNotNull { (place, routes) ->
                val shortest = routes.minByOrNull { it.distanceMeters } ?: return@mapNotNull null
                Triple(place, routes, shortest)
            }
            .minByOrNull { (_, _, shortest) -> shortest.distanceMeters }

        if (best == null) {
            _state.update { it.copy(routing = false) }
            return SpokenNavResult.NoRoute(byDistance.first())
        }

        val (destination, routes, shortest) = best
        _state.update {
            it.copy(
                phase = NavPhase.Preview(
                    origin = null,
                    destination = destination,
                    routes = routes,
                    // Point at the route that won, so startGuidance()'s `selected` drives the one
                    // this method measured rather than the provider's default.
                    selectedIndex = routes.indexOf(shortest).coerceAtLeast(0),
                ),
                routing = false,
                error = null,
            )
        }
        startGuidance()
        return SpokenNavResult.Started(destination, shortest)
    }

    /**
     * Radii tried in order, in km, stopping at the first that finds anything.
     *
     * Five covers a city, twenty a town and its outskirts, sixty is the last honest attempt
     * before "there isn't one near you" is simply true. Past that the answer stops being useful
     * to someone deciding whether they can get there.
     */
    private val NEARBY_RADII_KM = listOf(5, 20, 60)

    /**
     * How many of the crow-flies nearest get routed for real.
     *
     * Four is a compromise with a public routing instance, not a tuned number: enough that the
     * genuinely-closest-by-road is very likely inside it even when the geometry is awkward, few
     * enough that one spoken question is not a burst of traffic. They go out in parallel, so the
     * driver waits for one round trip regardless.
     */
    private const val MAX_NEAREST_CANDIDATES = 4

    // ---------------------------------------------------------------- guidance

    fun startGuidance() {
        val phase = _state.value.phase as? NavPhase.Preview ?: return
        val route = phase.selected
        repository.startGuidance(route)
        _state.update { it.copy(phase = NavPhase.Guiding(route = route), error = null) }
        // Guidance is the point navigation must survive being backgrounded, so raise the
        // foreground service + ongoing notification now (and only now).
        NavService.start(appContext)
    }

    fun endGuidance() {
        repository.stopGuidance()
        _state.update { it.copy(phase = NavPhase.Idle) }
        // Nothing left to keep the process alive for — drop the notification and let the OS
        // reclaim us normally.
        NavService.stop(appContext)
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

    private const val SEARCH_DEBOUNCE_MS = 280L
}
