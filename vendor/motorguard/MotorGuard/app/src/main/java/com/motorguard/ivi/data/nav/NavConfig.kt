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
        /**
         * Real GNSS via `LocationManager`. The default: it needs no emulator location mocking,
         * and on hardware with a receiver it is simply correct.
         *
         * The cost is that guidance only advances when the vehicle actually moves — sitting at
         * a desk, the route will not progress. The Nav screen offers a one-tap switch to
         * [SIMULATED] whenever it is waiting on a fix, so that is recoverable without a rebuild.
         */
        GNSS,

        /** Drives the puck along the active route. Works at a desk, with no GPS and no sky. */
        SIMULATED,
    }

    var stack: Stack = Stack.OSS
    var mapBackend: MapBackend = MapBackend.MAPLIBRE

    /** Changing this at runtime is supported — call [NavProviders.invalidate] afterwards. */
    var locationMode: LocationMode = LocationMode.GNSS

    // ---------------------------------------------------------------- OSS endpoints
    //
    // All three are public fair-use instances: fine for development and a demo, not for a
    // fleet. For production point them at your own Valhalla + Photon + tile server; the
    // request/response shapes are identical.

    /** OpenFreeMap vector tiles (OpenMapTiles schema). No key, no quota, no registration. */
    var tileJsonUrl: String = "https://tiles.openfreemap.org/planet"
    var glyphsUrl: String = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /**
     * FOSSGIS's public Valhalla. Fair use, rate-limited, wants an identifying client id.
     *
     * Note the **`valhalla1`**. The bare `valhalla.openstreetmap.de` is the demo *web app*: it
     * answers `/route` with an HTML page and a 200 status, which sails past a status-code check
     * and then blows up as "String cannot be converted to JSONObject". The API hosts are
     * `valhalla1`/`valhalla2`/`valhalla3`.
     */
    var valhallaBaseUrl: String = "https://valhalla1.openstreetmap.de"

    /** komoot's public Photon. Built for search-as-you-type; throttled if hammered. */
    var photonBaseUrl: String = "https://photon.komoot.io"

    /** Sent on every request so the public instances can identify (and contact) us. */
    var clientId: String = "motorguard-ivi"
    var userAgent: String = "MotorGuardIVI/0.1 (AAOS; +https://github.com/motorguard)"

    /** Shown over the map — required by the ODbL for OSM-derived tiles and routes. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    // ---------------------------------------------------------------- demo behaviour

    /**
     * Where the map looks while [LocationMode.GNSS] is still waiting for its first fix, and
     * where the simulator parks the car when no route is active. Superseded by the real
     * position the moment one arrives.
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
