#include "assistant/Assistant.hpp"

#include <algorithm>
#include <sstream>

#include "assistant/Log.hpp"

namespace assistant {

namespace {
constexpr const char* TAG = "asst";

UiSeverity toUi(Severity s) {
    if (s >= Severity::Urgent) return UiSeverity::Critical;
    if (s >= Severity::Soon)   return UiSeverity::Warning;
    return UiSeverity::Info;
}
}  // namespace

Assistant::Assistant(AssistantDeps deps) : deps_(deps) {}

void Assistant::start() {
    deps_.vehicle.subscribe([this](const FaultEvent& e) { onFault(e); });
    deps_.vehicle.start();
    // Seed state from any faults already active at startup.
    for (const auto& f : deps_.vehicle.activeFaults()) onFault(f);
    LOG_I(TAG, "assistant started");
}

void Assistant::stop() {
    deps_.vehicle.stop();
}

void Assistant::onFault(const FaultEvent& e) {
    Assessment a = deps_.diagnostics.assess(e);
    {
        std::lock_guard<std::mutex> lock(mtx_);
        // Replace an existing entry with the same code, else append.
        auto it = std::find_if(faults_.begin(), faults_.end(),
                               [&](const FaultEvent& f) { return f.code == e.code; });
        if (it != faults_.end()) *it = e; else faults_.push_back(e);
    }
    LOG_I(TAG, "fault %s (%s) -> severity %s", e.code.c_str(),
          toString(e.source), toString(a.severity));

    // Proactively alert only for the genuinely urgent stuff; advisories wait for
    // the driver to ask, so we don't nag.
    if (a.severity >= Severity::Urgent) {
        std::string msg = composeExplanation(a, e);
        std::lock_guard<std::mutex> lock(mtx_);
        focus_code_ = e.code;
        say(msg, toUi(a.severity));
    }
}

std::vector<FaultEvent> Assistant::currentFaults() const {
    std::lock_guard<std::mutex> lock(mtx_);
    return faults_;
}

std::optional<FaultEvent> Assistant::focusFault() const {
    // Caller holds the lock.
    if (faults_.empty()) return std::nullopt;
    const FaultEvent* best = nullptr;
    Severity best_sev = Severity::Info;
    for (const auto& f : faults_) {
        Severity s = deps_.diagnostics.assess(f).severity;
        if (!best || s > best_sev ||
            (s == best_sev && f.timestamp_ms > best->timestamp_ms)) {
            best = &f; best_sev = s;
        }
    }
    return best ? std::optional<FaultEvent>(*best) : std::nullopt;
}

std::string Assistant::handleUtterance(const std::string& text) {
    IntentResult r = deps_.intents.match(text);
    LOG_I(TAG, "utterance='%s' -> intent=%s conf=%.2f", text.c_str(),
          toString(r.intent), r.confidence);

    switch (r.intent) {
        case Intent::ExplainWarning: return doExplain(r.code_slot);
        case Intent::AssessSeverity: return doAssessSeverity();
        case Intent::FindService:    return doFindService();
        case Intent::ListFaults:     return doListFaults();
        case Intent::RepeatLast: {
            std::lock_guard<std::mutex> lock(mtx_);
            std::string resp = last_response_.empty()
                ? "I haven't said anything yet."
                : last_response_;
            deps_.tts.speak(resp);
            return resp;
        }
        case Intent::Help:   return doHelp();
        case Intent::Cancel: { std::string r2 = "Okay."; say(r2, UiSeverity::Info); return r2; }
        case Intent::Unknown:
        default: {
            // No list. It named three things out of a set that has grown well past them, and
            // reciting a menu at someone who has just not been understood delays the retry
            // that is the only thing they want. Intent::Help still gives the full answer.
            std::string resp = "I didn't catch that.";
            say(resp, UiSeverity::Info);
            return resp;
        }
    }
}

std::string Assistant::doExplain(const std::optional<std::string>& code_slot) {
    std::lock_guard<std::mutex> lock(mtx_);
    FaultEvent target;

    if (code_slot) {
        // Driver named a code. Use a live event if we have one, else synthesise.
        auto it = std::find_if(faults_.begin(), faults_.end(),
                               [&](const FaultEvent& f) { return f.code == *code_slot; });
        if (it != faults_.end()) target = *it;
        else { target.code = *code_slot; target.source = FaultSource::ActiveDtc; }
    } else {
        auto f = focusFault();
        if (!f) {
            std::string resp = "Good news, I'm not seeing any active warnings right now.";
            say(resp, UiSeverity::Info);
            return resp;
        }
        target = *f;
    }

    focus_code_ = target.code;
    Assessment a = deps_.diagnostics.assess(target);
    std::string resp = composeExplanation(a, target);
    say(resp, toUi(a.severity));
    return resp;
}

std::string Assistant::doAssessSeverity() {
    std::lock_guard<std::mutex> lock(mtx_);
    std::optional<FaultEvent> target;
    if (focus_code_) {
        auto it = std::find_if(faults_.begin(), faults_.end(),
                               [&](const FaultEvent& f) { return f.code == *focus_code_; });
        if (it != faults_.end()) target = *it;
    }
    if (!target) target = focusFault();
    if (!target) {
        std::string resp = "There's nothing active to worry about at the moment.";
        say(resp, UiSeverity::Info);
        return resp;
    }
    focus_code_ = target->code;
    Assessment a = deps_.diagnostics.assess(*target);
    std::string resp = composeSeverity(a);
    say(resp, toUi(a.severity));
    return resp;
}

std::string Assistant::doFindService() {
    std::lock_guard<std::mutex> lock(mtx_);
    if (!deps_.location) {
        std::string resp =
            "I can't look up nearby garages in this setup yet, but based on the "
            "warning you should have it seen to soon.";
        say(resp, UiSeverity::Info);
        return resp;
    }
    std::string svc = appendService();
    std::string resp = svc.empty()
        ? "I couldn't find a service station nearby right now."
        : "Here's what's close by." + svc;
    say(resp, UiSeverity::Info);
    return resp;
}

std::string Assistant::doListFaults() {
    std::lock_guard<std::mutex> lock(mtx_);
    if (faults_.empty()) {
        std::string resp = "I'm not seeing any faults at the moment. Everything looks fine.";
        say(resp, UiSeverity::Info);
        return resp;
    }
    std::ostringstream os;
    os << "I'm currently aware of " << faults_.size()
       << (faults_.size() == 1 ? " item: " : " items: ");
    // Sort by severity desc for the readout.
    std::vector<std::pair<Severity, std::string>> lines;
    for (const auto& f : faults_) {
        Assessment a = deps_.diagnostics.assess(f);
        std::string tag = (f.source == FaultSource::Predicted) ? " (predicted)" : "";
        lines.emplace_back(a.severity, a.info.name + tag);
    }
    std::sort(lines.begin(), lines.end(),
              [](auto& x, auto& y) { return x.first > y.first; });
    for (size_t i = 0; i < lines.size(); ++i) {
        os << lines[i].second;
        if (i + 1 < lines.size()) os << "; ";
    }
    os << ". Ask me about any of them for more detail.";
    std::string resp = os.str();
    say(resp, UiSeverity::Info);
    return resp;
}

std::string Assistant::doHelp() const {
    std::string resp =
        "I'm your maintenance assistant. Ask things like: what's this warning "
        "light, is it serious, can I keep driving, or where's the nearest garage. "
        "I'll also speak up on my own if something urgent comes up.";
    // const method: speak but don't touch state.
    deps_.tts.speak(resp);
    return resp;
}

// ---------------------------------------------------------------------------
// Response composition
// ---------------------------------------------------------------------------

std::string Assistant::severityLead(Severity s) const {
    switch (s) {
        case Severity::StopNow:
            return "This is urgent. ";
        case Severity::Urgent:
            return "This is serious. ";
        case Severity::Soon:
            return "It's worth acting on soon. ";
        case Severity::Advisory:
            return "It's minor. ";
        case Severity::Info:
        default:
            return "";
    }
}

std::string Assistant::composeExplanation(const Assessment& a, const FaultEvent& e) {
    std::ostringstream os;

    if (e.source == FaultSource::Predicted) {
        os << "This is a heads-up rather than an active fault. ";
    }

    // What it is. The explanation is the ONLY text a language model may touch,
    // and only for low-severity faults. Severity and action below are composed
    // from the rules engine's verdict and never pass through the model.
    std::string explanation = a.info.explanation;
    if (deps_.phrasing) explanation = deps_.phrasing->rephrase(explanation, a.severity);

    if (a.found) {
        os << a.info.name << ". " << explanation << " ";
    } else {
        os << explanation << " ";
    }

    // How bad + what to do.
    os << severityLead(a.severity);
    if (!a.action.empty()) os << a.action;

    // Nearest garage, but only when it's actually helpful (drive-affecting or
    // urgent) and we have a location provider.
    if (deps_.location &&
        (a.severity >= Severity::Soon || a.info.drive_affecting)) {
        // NOTE: appendService acquires no lock; callers hold mtx_.
        std::string svc;
        auto stations = deps_.location->nearestServiceStations(1);
        if (!stations.empty()) {
            const auto& st = stations.front();
            std::ostringstream s2;
            s2 << " There's a service station, " << st.name << ", about "
               << static_cast<int>(st.distance_km + 0.5) << " kilometres away";
            if (!st.open_now) s2 << ", though it may be closed right now";
            s2 << ".";
            svc = s2.str();
        }
        os << svc;
    }
    return os.str();
}

std::string Assistant::composeSeverity(const Assessment& a) const {
    std::ostringstream os;
    switch (a.severity) {
        case Severity::StopNow:
            os << "No — you should stop as soon as it's safe. " << a.action;
            break;
        case Severity::Urgent:
            os << "You can drive carefully for now, but don't put it off. " << a.action;
            break;
        case Severity::Soon:
            os << "Yes, you can keep driving, but get it looked at soon. " << a.action;
            break;
        case Severity::Advisory:
        case Severity::Info:
        default:
            os << "Yes, it's fine to keep driving. " << a.action;
            break;
    }
    if (!a.escalation_reason.empty())
        os << " I've raised the urgency because of the current sensor readings.";
    return os.str();
}

std::string Assistant::appendService() {
    // Caller holds the lock. Returns a spoken list of nearby stations.
    if (!deps_.location) return "";
    auto stations = deps_.location->nearestServiceStations(3);
    if (stations.empty()) return "";
    std::ostringstream os;
    for (size_t i = 0; i < stations.size(); ++i) {
        const auto& st = stations[i];
        os << " " << st.name << ", " << static_cast<int>(st.distance_km + 0.5)
           << " kilometres away" << (st.open_now ? "" : " (may be closed)") << ".";
    }
    return os.str();
}

void Assistant::say(const std::string& text, UiSeverity ui_sev) {
    // Caller holds the lock when mutating last_response_.
    last_response_ = text;
    if (deps_.ui) deps_.ui->showMessage(text, ui_sev);
    deps_.tts.speak(text);
}

}  // namespace assistant
