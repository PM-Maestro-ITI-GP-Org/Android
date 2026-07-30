package com.motorguard.ivi.data.nav

/**
 * Every knob the navigation feature has, in one file. Nothing else in `data/nav` reads a
 * literal endpoint, provider choice or demo constant — change it here and the whole stack
 * follows. Settings (owner E) can flip these at runtime later.
 */
object NavConfig {

    // ---------------------------------------------------------------- provider selection

    /** Which implementation set [NavProviders] hands out. */
    enum class Stack {
        /** MapLibre + Valhalla + Photon. All OSS, no Play Services — the AOSP-safe default. */
        OSS,

        /**
         * Google Maps SDK + Directions + Places. Not implemented: this AOSP image has no
         * Play Services, so it cannot run today. Kept as a declared option so the seam stays
         * honest — see `google/GoogleNavProviders.kt` for the exact TODO list.
         */
        GOOGLE,
    }

    /** How the map is drawn. */
    enum class MapBackend {
        /** MapLibre Native, vector tiles over the network, styled from [com.motorguard.ivi.ui.theme.Tokens]. */
        MAPLIBRE,

        /**
         * The built-in Canvas renderer. No GL, no network, no tiles — an abstract street
         * grid with the real route drawn on top. Used as the automatic fallback when
         * MapLibre cannot initialize (bad GL driver, no tiles reachable) so the Nav tab is
         * never an empty rectangle on the bench.
         */
        STYLIZED,
    }

    /** Where [VehiclePosition]s come from. */
    enum class LocationMode {
        /** Drives the puck along the active route. Works at a desk, with no GPS and no sky. */
        SIMULATED,

        /** Real GNSS via `LocationManager` (needs a receiver on the Pi + location permission). */
        GNSS,
    }

    var stack: Stack = Stack.OSS
    var mapBackend: MapBackend = MapBackend.MAPLIBRE
    var locationMode: LocationMode = LocationMode.SIMULATED

    // ---------------------------------------------------------------- OSS endpoints
    //
    // All three are public fair-use instances: fine for development and a demo, not for a
    // fleet. For production point them at your own Valhalla + Photon + tile server; the
    // request/response shapes are identical.

    /** OpenFreeMap vector tiles (OpenMapTiles schema). No key, no quota, no registration. */
    var tileJsonUrl: String = "https://tiles.openfreemap.org/planet"
    var glyphsUrl: String = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /** FOSSGIS's public Valhalla. Fair use, rate-limited, wants an identifying client id. */
    var valhallaBaseUrl: String = "https://valhalla.openstreetmap.de"

    /** komoot's public Photon. Built for search-as-you-type; throttled if hammered. */
    var photonBaseUrl: String = "https://photon.komoot.io"

    /** Sent on every request so the public instances can identify (and contact) us. */
    var clientId: String = "motorguard-ivi"
    var userAgent: String = "MotorGuardIVI/0.1 (AAOS; +https://github.com/motorguard)"

    /** Shown over the map — required by the ODbL for OSM-derived tiles and routes. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    // ---------------------------------------------------------------- demo behaviour

    /**
     * Where the car sits before a route is picked. Replace with the first real GNSS fix once
     * a receiver is attached; until then the whole feature needs *somewhere* to start.
     */
    var defaultOrigin: GeoPoint = GeoPoint(30.0444, 31.2357) // Cairo

    /**
     * How much faster than real time the simulator drives. 1f is realistic and unwatchable
     * in a review; 8f covers a 60 km route in about 8 minutes. ETA and distances stay
     * truthful — only the clock the car experiences is compressed.
     */
    var simulationSpeedFactor: Float = 8f

    /** Simulator tick. 10 Hz is smooth under the camera easing and cheap on the Pi. */
    var simulationTickMs: Long = 100L
}
