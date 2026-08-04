#pragma once

#include <vector>

#include "assistant/Ports.hpp"

namespace assistant::adapters {

// Offline stand-in for a POI/location service. Returns a fixed, pre-sorted list
// of nearby service stations. A real offline build would query a local POI
// dataset (e.g. an SQLite table of stations) filtered by GPS position; this
// adapter keeps the same interface so the Assistant is unaffected.
class StaticLocationProvider : public ILocationProvider {
public:
    StaticLocationProvider() {
        stations_ = {
            {"AutoCare Service Centre", "12 Ring Road", 2.4, true},
            {"QuickFix Garage",         "Exit 7, Motorway", 6.1, true},
            {"City Motors Workshop",    "45 Industrial Ave", 9.8, false},
        };
    }

    std::vector<ServiceStation> nearestServiceStations(int max) override {
        std::vector<ServiceStation> out;
        for (const auto& s : stations_) {
            if (static_cast<int>(out.size()) >= max) break;
            out.push_back(s);
        }
        return out;
    }

    bool hasFix() const override { return true; }

private:
    std::vector<ServiceStation> stations_;
};

}  // namespace assistant::adapters
