package com.motorguard.ivi.ui.nav

import com.motorguard.ivi.data.nav.NavProgress
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.data.nav.Route
import com.motorguard.ivi.data.nav.VehiclePosition

/**
 * The four states the Nav tab can be in. Modelled as a sealed hierarchy rather than a bag of
 * nullable fields so the screen cannot render "guiding, but with no route" — the compiler
 * refuses to construct it.
 */
sealed interface NavPhase {

    /** Map, car, and one affordance: "Where to?". */
    data object Idle : NavPhase

    /** Search panel open over the map. */
    data class Searching(
        val query: String = "",
        val results: List<Place> = emptyList(),
        val loading: Boolean = false,
    ) : NavPhase

    /** Routes computed; the driver picks one and starts. */
    data class Preview(
        val destination: Place,
        val routes: List<Route>,
        val selectedIndex: Int = 0,
    ) : NavPhase {
        val selected: Route get() = routes[selectedIndex.coerceIn(routes.indices)]
    }

    /** Turn-by-turn. [progress] is null only for the instant before the first position lands. */
    data class Guiding(
        val route: Route,
        val progress: NavProgress? = null,
        val muted: Boolean = false,
        /**
         * True: chase camera, heading-up, car anchored low on screen. False: the whole
         * remaining route framed north-up. The map button toggles between them — "where am I
         * in this trip" is a question drivers ask mid-route, and it is the only reason to move
         * the camera off the car.
         */
        val following: Boolean = true,
    ) : NavPhase
}

data class NavUiState(
    val phase: NavPhase = NavPhase.Idle,
    val position: VehiclePosition? = null,
    /** Set while a route request is in flight — the Start/search UI shows a spinner. */
    val routing: Boolean = false,
    /** Transient failure text for the banner. Cleared by the next successful action. */
    val error: String? = null,
)
