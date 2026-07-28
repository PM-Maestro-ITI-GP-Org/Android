// CarDataRepository — shared, owner: core team
// The ONLY place that talks to CarPropertyManager. Fragments observe from here.
package com.motorguard.ivi.data

/**
 * Wraps android.car.Car + CarPropertyManager. Exposes StateFlows so every
 * fragment observes the same live vehicle state without touching the HAL.
 *   - vehicleState : SoC, range, tire PSI x4, cell temp, brake wear, doors/locks
 *   - hvacState    : zone temps, fan, A/C, recirc, defrost
 * Subscribe on property-change callbacks; on the Pi, back with the mock VHAL
 * until real CAN hardware is wired in.
 */
class CarDataRepository {
    // val vehicleState: StateFlow<VehicleState>
    // val hvacState: StateFlow<HvacState>
    // fun setHvacTemp(zone: Zone, celsius: Float) { ... }
}
