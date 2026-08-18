#pragma once

#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "FaultEvent.hpp"

struct sqlite3;  // fwd decl; keeps sqlite out of the public header

namespace assistant {

// Static knowledge about a fault code, loaded from the DTC database.
struct FaultInfo {
    std::string code;
    std::string name;             // "Engine coolant over-temperature"
    std::string system;           // "Cooling", "Brakes", "Emissions", ...
    std::string explanation;      // plain-language, driver-friendly
    Severity    base_severity = Severity::Advisory;
    bool        drive_affecting = false;  // does it change how they should drive?
    std::string base_action;      // default recommended action text
};

// The verdict the engine produces for one fault: the static info plus the
// final, rules-adjusted severity and the action to recommend. This is what the
// responder turns into speech.
struct Assessment {
    FaultInfo   info;
    Severity    severity = Severity::Advisory;  // AFTER rules
    std::string action;                          // AFTER rules (may differ from base)
    std::string escalation_reason;               // why severity changed, if it did
    bool        found = false;                   // was the code in the database?
};

// A safety rule can inspect the event's freeze-frame and raise severity or swap
// the recommended action. Rules may only escalate (the engine enforces this),
// so a hallucinating phrasing layer can never talk the driver *out* of stopping.
struct SafetyRule {
    std::string name;
    // Applies to this code? Empty match_code means "any code".
    std::string match_code;
    // Given the event, the current assessment and the reply language, optionally
    // return a stronger severity plus the (already-localized) action text to
    // use instead. Return std::nullopt to leave it unchanged.
    std::function<std::optional<std::pair<Severity, std::string>>(
        const FaultEvent&, const Assessment&, Language)> evaluate;
};

class DiagnosticsEngine {
public:
    DiagnosticsEngine();
    ~DiagnosticsEngine();

    DiagnosticsEngine(const DiagnosticsEngine&) = delete;
    DiagnosticsEngine& operator=(const DiagnosticsEngine&) = delete;

    // Open the DTC database. Pass ":memory:" to build an in-memory DB (then call
    // loadSeedSql to populate it). Returns false on failure.
    bool open(const std::string& db_path);

    // Execute a SQL script (used to seed an in-memory DB from data/dtc_seed.sql).
    bool execScript(const std::string& sql);

    // Register a safety rule. Rules run in registration order; each may only
    // raise severity relative to the running assessment.
    void addRule(SafetyRule rule);

    // Look up static info for a code, localized to [language].
    std::optional<FaultInfo> lookup(const std::string& code, Language language) const;

    // Full assessment: DB lookup + rules applied to this specific event.
    Assessment assess(const FaultEvent& event, Language language) const;

    int faultCount() const;

private:
    sqlite3* db_ = nullptr;
    std::vector<SafetyRule> rules_;
};

// Registers the built-in safety rules (overheat, oil pressure, brakes, etc.).
void installDefaultRules(DiagnosticsEngine& engine);

}  // namespace assistant
