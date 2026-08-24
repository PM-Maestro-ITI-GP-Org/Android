#pragma once

#include <vector>

#include "assistant/Ports.hpp"

namespace assistant::adapters {

// Offline stand-in for a POI/location service. Returns a fixed, pre-sorted list
// of nearby service stations. A real offline build would query a local POI
// dataset (e.g. an SQLite table of stations) filtered by GPS position; this
// adapter keeps the same interface so the Assistant is unaffected.
// Deliberately empty, and kept only so AssistantDeps still has a provider to point at.
//
// It used to return three hardcoded stations -- "AutoCare Service Centre, 12 Ring Road, 2.4 km"
// and two more -- with hasFix() hardwired to true. That is fabricated data presented to a driver
// as a place they can drive to, and the 2.4 km was a number attached to a location nobody had.
//
// The real answer now comes from the Android side: NavVoice.nearestOf hears the question,
// NavSession.navigateToNearest searches the live geocoder around the vehicle's actual fix, sorts
// by true distance, and routes there. That path refuses to answer without a position rather than
// inventing one, which is the behaviour this class could not offer.
//
// If an offline POI dataset ever lands, this is where it plugs in -- the interface was always
// right, only the contents were made up.
class StaticLocationProvider : public ILocationProvider {
public:
    std::vector<ServiceStation> nearestServiceStations(int /*max*/) override { return {}; }

    // No position, honestly. The caller's "I don't know where you are" branch is correct here
    // and was previously unreachable.
    bool hasFix() const override { return false; }
};

}  // namespace assistant::adapters
