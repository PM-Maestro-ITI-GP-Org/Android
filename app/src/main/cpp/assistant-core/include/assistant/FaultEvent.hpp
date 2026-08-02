#pragma once

#include <cstdint>
#include <map>
#include <string>

namespace assistant {

// Where a fault came from. The predictive-maintenance system feeds PREDICTED
// events; the cluster / OBD-II layer feeds ACTIVE_DTC events. Both arrive on the
// same IVehicleData port so the dialog logic never has to care about transport.
enum class FaultSource {
    ActiveDtc,   // a diagnostic trouble code that is active right now
    Predicted    // a forecast from the predictive-maintenance model
};

// Severity is the single axis the assistant reasons about when deciding what to
// tell the driver. It is deliberately ordered: higher value == more urgent, so
// the rules engine can escalate with std::max and never silently downgrade.
enum class Severity {
    Info      = 0,  // FYI, no action ("washer fluid low")
    Advisory  = 1,  // book service at your convenience
    Soon      = 2,  // service within days / before long trips
    Urgent    = 3,  // stop driving soon, get it looked at today
    StopNow   = 4   // pull over safely and stop as soon as it is safe
};

const char* toString(Severity s) noexcept;
const char* toString(FaultSource s) noexcept;

// A single fault as delivered by whatever produces it. Transport-agnostic on
// purpose: a replay file, a localhost socket, or a CAN adapter all build this
// same struct. Keep it a plain value type so it is trivially copyable/queueable.
struct FaultEvent {
    std::string  code;             // "P0217", or a custom predictive code like "PRED_BRAKE_WEAR"
    FaultSource  source = FaultSource::ActiveDtc;
    Severity     severity_hint = Severity::Advisory; // sender's opinion; rules make the final call
    std::int64_t timestamp_ms = 0;

    // Snapshot of relevant sensor values at the moment of the fault. Keys are
    // free-form (e.g. "coolant_temp_c", "rpm", "km_to_service"). The rules
    // engine reads these to escalate/contextualise. Empty is fine.
    std::map<std::string, double> freeze_frame;

    double frame(const std::string& key, double fallback = 0.0) const {
        auto it = freeze_frame.find(key);
        return it == freeze_frame.end() ? fallback : it->second;
    }
    bool hasFrame(const std::string& key) const {
        return freeze_frame.find(key) != freeze_frame.end();
    }
};

}  // namespace assistant
