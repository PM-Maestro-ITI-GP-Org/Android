#include "assistant/DiagnosticsEngine.hpp"

#include <sqlite3.h>

#include <algorithm>

#include "assistant/Log.hpp"

namespace assistant {

namespace {
constexpr const char* TAG = "diag";

Severity severityFromInt(int v) {
    if (v < 0) v = 0;
    if (v > 4) v = 4;
    return static_cast<Severity>(v);
}

// Escalate-only merge: never lets a rule lower severity.
Severity raiseOnly(Severity current, Severity candidate) {
    return static_cast<Severity>(
        std::max(static_cast<int>(current), static_cast<int>(candidate)));
}
}  // namespace

DiagnosticsEngine::DiagnosticsEngine() = default;

DiagnosticsEngine::~DiagnosticsEngine() {
    if (db_) sqlite3_close(db_);
}

bool DiagnosticsEngine::open(const std::string& db_path) {
    if (db_) {
        sqlite3_close(db_);
        db_ = nullptr;
    }
    int rc = sqlite3_open(db_path.c_str(), &db_);
    if (rc != SQLITE_OK) {
        LOG_E(TAG, "cannot open db '%s': %s", db_path.c_str(),
              db_ ? sqlite3_errmsg(db_) : "unknown");
        if (db_) { sqlite3_close(db_); db_ = nullptr; }
        return false;
    }
    LOG_I(TAG, "opened fault database: %s", db_path.c_str());
    return true;
}

bool DiagnosticsEngine::execScript(const std::string& sql) {
    if (!db_) return false;
    char* err = nullptr;
    int rc = sqlite3_exec(db_, sql.c_str(), nullptr, nullptr, &err);
    if (rc != SQLITE_OK) {
        LOG_E(TAG, "seed script failed: %s", err ? err : "?");
        sqlite3_free(err);
        return false;
    }
    LOG_I(TAG, "loaded %d fault definitions", faultCount());
    return true;
}

void DiagnosticsEngine::addRule(SafetyRule rule) {
    rules_.push_back(std::move(rule));
}

int DiagnosticsEngine::faultCount() const {
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT COUNT(*) FROM faults;", -1, &st, nullptr) != SQLITE_OK)
        return 0;
    int n = 0;
    if (sqlite3_step(st) == SQLITE_ROW) n = sqlite3_column_int(st, 0);
    sqlite3_finalize(st);
    return n;
}

std::optional<FaultInfo> DiagnosticsEngine::lookup(const std::string& code) const {
    if (!db_) return std::nullopt;
    static const char* kSql =
        "SELECT code,name,system,explanation,base_severity,drive_affecting,base_action "
        "FROM faults WHERE code = ?1;";
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, kSql, -1, &st, nullptr) != SQLITE_OK) {
        LOG_E(TAG, "prepare failed: %s", sqlite3_errmsg(db_));
        return std::nullopt;
    }
    sqlite3_bind_text(st, 1, code.c_str(), -1, SQLITE_TRANSIENT);

    std::optional<FaultInfo> result;
    if (sqlite3_step(st) == SQLITE_ROW) {
        FaultInfo fi;
        auto text = [&](int col) -> std::string {
            const unsigned char* s = sqlite3_column_text(st, col);
            return s ? reinterpret_cast<const char*>(s) : "";
        };
        fi.code            = text(0);
        fi.name            = text(1);
        fi.system          = text(2);
        fi.explanation     = text(3);
        fi.base_severity   = severityFromInt(sqlite3_column_int(st, 4));
        fi.drive_affecting = sqlite3_column_int(st, 5) != 0;
        fi.base_action     = text(6);
        result = std::move(fi);
    }
    sqlite3_finalize(st);
    return result;
}

Assessment DiagnosticsEngine::assess(const FaultEvent& event) const {
    Assessment a;
    auto info = lookup(event.code);
    if (!info) {
        // Unknown code: fall back to the sender's hint so we still say something
        // safe rather than nothing.
        a.found = false;
        a.info.code = event.code;
        a.info.name = "Unrecognised fault code";
        a.info.system = "Unknown";
        a.info.explanation =
            "I don't have details on this specific code, so I can't fully explain it.";
        a.severity = event.severity_hint;
        a.action = (event.severity_hint >= Severity::Urgent)
            ? "To be safe, treat this as serious and have the car checked as soon as you can."
            : "Have the car checked at a service centre when convenient.";
        LOG_W(TAG, "unknown code '%s', using sender hint", event.code.c_str());
        return a;
    }

    a.found    = true;
    a.info     = *info;
    a.severity = info->base_severity;
    a.action   = info->base_action;

    // Apply safety rules in order. Each may only raise severity / swap action.
    for (const auto& rule : rules_) {
        if (!rule.match_code.empty() && rule.match_code != event.code) continue;
        if (!rule.evaluate) continue;
        auto out = rule.evaluate(event, a);
        if (!out) continue;
        Severity raised = raiseOnly(a.severity, out->first);
        if (raised != a.severity || !out->second.empty()) {
            if (raised != a.severity) {
                a.severity = raised;
                a.escalation_reason = rule.name;
            }
            if (!out->second.empty()) a.action = out->second;
            LOG_I(TAG, "rule '%s' -> severity %s for %s",
                  rule.name.c_str(), toString(a.severity), event.code.c_str());
        }
    }
    return a;
}

// ---------------------------------------------------------------------------
// Default safety rules. These encode the "rules decide the recommendation"
// half of the hybrid: they read live freeze-frame values and escalate. They can
// only raise severity, so no phrasing layer can ever downgrade a stop-now.
// ---------------------------------------------------------------------------
void installDefaultRules(DiagnosticsEngine& engine) {
    using Ret = std::optional<std::pair<Severity, std::string>>;

    // Coolant over-temp: escalate to StopNow past a hard limit.
    engine.addRule({
        "coolant over-temperature limit", "P0217",
        [](const FaultEvent& e, const Assessment&) -> Ret {
            if (e.frame("coolant_temp_c", 0.0) >= 118.0) {
                return std::make_pair(
                    Severity::StopNow,
                    "The engine is critically hot. Pull over safely and switch it "
                    "off now to avoid permanent damage.");
            }
            return std::nullopt;
        }});

    // Predicted coolant trend becomes more urgent if it is climbing fast.
    engine.addRule({
        "coolant trend acceleration", "PRED_COOLANT_TREND",
        [](const FaultEvent& e, const Assessment&) -> Ret {
            if (e.frame("trend_c_per_100km", 0.0) >= 3.0) {
                return std::make_pair(
                    Severity::Soon,
                    "The engine temperature is climbing faster than usual. Have the "
                    "cooling system checked in the next day or two.");
            }
            return std::nullopt;
        }});

    // Low system voltage: if it is very low, the car may stall soon.
    engine.addRule({
        "critical system voltage", "P0562",
        [](const FaultEvent& e, const Assessment&) -> Ret {
            if (e.hasFrame("voltage") && e.frame("voltage") <= 11.5) {
                return std::make_pair(
                    Severity::Urgent,
                    "The battery is draining and the car could stall. Head to the "
                    "nearest service point now and avoid switching the engine off.");
            }
            return std::nullopt;
        }});

    // Misfire with a flashing lamp (catalyst-damaging) is always urgent+.
    engine.addRule({
        "active misfire severity", "P0300",
        [](const FaultEvent& e, const Assessment&) -> Ret {
            if (e.frame("misfire_flashing", 0.0) >= 1.0) {
                return std::make_pair(
                    Severity::Urgent,
                    "The misfire is severe enough to damage the exhaust system. "
                    "Reduce speed and get it looked at today.");
            }
            return std::nullopt;
        }});
}

}  // namespace assistant
