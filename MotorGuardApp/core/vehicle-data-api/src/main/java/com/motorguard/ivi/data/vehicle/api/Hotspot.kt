package com.motorguard.ivi.data.vehicle.api

/**
 * The 8 tappable components on the interactive car (per docs/05-diagnostics.md:
 * battery, tires x4, motor, brakes, doors). Doors is ONE hotspot; its telemetry
 * carries the per-door list. Per-corner brakes is a possible future enhancement,
 * out of scope for phase 1.
 */
enum class Hotspot(val label: String) {
    BATTERY("Battery"),
    MOTOR("Motor"),
    TIRE_FL("Front-left tire"),
    TIRE_FR("Front-right tire"),
    TIRE_RL("Rear-left tire"),
    TIRE_RR("Rear-right tire"),
    BRAKES("Brakes"),
    DOORS("Doors"),
    ;

    companion object {
        /** Exact wheel-corner hotspot order used for the tires flow list. */
        val tireCorners = listOf(TIRE_FL, TIRE_FR, TIRE_RL, TIRE_RR)
    }
}

/** Universal semantic severity — drives dot color, card color and alert color. */
enum class Severity { OK, CAUTION, CRITICAL }
