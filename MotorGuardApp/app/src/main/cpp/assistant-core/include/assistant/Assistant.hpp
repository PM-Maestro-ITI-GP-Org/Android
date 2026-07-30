#pragma once

#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "DiagnosticsEngine.hpp"
#include "FaultEvent.hpp"
#include "IntentMatcher.hpp"
#include "Phrasing.hpp"
#include "Ports.hpp"

namespace assistant {

// Dependencies are injected as references to interfaces. The Assistant owns no
// platform knowledge: swap the adapters and the same object runs on Android or
// Linux. location and ui are optional (may be null) so the error-explaining
// build can run without them.
struct AssistantDeps {
    DiagnosticsEngine& diagnostics;
    IIntentMatcher&    intents;
    ITextToSpeech&     tts;
    IVehicleData&      vehicle;
    ILocationProvider* location = nullptr;  // optional
    IUiBridge*         ui = nullptr;         // optional
    IPhrasing*         phrasing = nullptr;   // optional; null == say the DB text verbatim
};

class Assistant {
public:
    explicit Assistant(AssistantDeps deps);

    // Start listening to the vehicle-data port. Fault events update internal
    // state and, if severe, trigger a proactive spoken alert.
    void start();
    void stop();

    // Main entry point for a recognised driver utterance. Returns the text of
    // the response (also spoken via TTS). Thread-safe.
    std::string handleUtterance(const std::string& text);

    // Feed a raw fault event directly (used by tests and by the subscription).
    void onFault(const FaultEvent& e);

    // Snapshot of what the assistant currently believes is wrong.
    std::vector<FaultEvent> currentFaults() const;

private:
    // Intent handlers.
    std::string doExplain(const std::optional<std::string>& code_slot);
    std::string doAssessSeverity();
    std::string doFindService();
    std::string doListFaults();
    std::string doHelp() const;

    // Response composition (the "explaining" logic).
    std::string composeExplanation(const Assessment& a, const FaultEvent& e);
    std::string composeSeverity(const Assessment& a) const;
    std::string severityLead(Severity s) const;
    std::string appendService() ;

    // Pick the fault the driver most likely means when they don't name a code:
    // highest severity, then most recent.
    std::optional<FaultEvent> focusFault() const;

    void say(const std::string& text, UiSeverity ui_sev);

    AssistantDeps deps_;
    mutable std::mutex mtx_;
    std::vector<FaultEvent> faults_;         // current active + predicted
    std::optional<std::string> focus_code_;  // the fault under discussion
    std::string last_response_;              // for "say that again"
};

}  // namespace assistant
