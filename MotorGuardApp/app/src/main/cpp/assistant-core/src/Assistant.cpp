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

void Assistant::setLanguage(Language language) {
    std::lock_guard<std::mutex> lock(mtx_);
    language_ = language;
}

Language Assistant::language() const {
    std::lock_guard<std::mutex> lock(mtx_);
    return language_;
}

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
    Assessment a = deps_.diagnostics.assess(e, language());
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
        Severity s = deps_.diagnostics.assess(f, language_).severity;
        if (!best || s > best_sev ||
            (s == best_sev && f.timestamp_ms > best->timestamp_ms)) {
            best = &f; best_sev = s;
        }
    }
    return best ? std::optional<FaultEvent>(*best) : std::nullopt;
}

std::string Assistant::handleUtterance(const std::string& text) {
    const Language lang = language();
    const bool ar = (lang == Language::ArabicEgypt);
    IntentResult r = deps_.intents.match(text, lang);
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
                ? (ar ? "لسه مقلتش حاجة." : "I haven't said anything yet.")
                : last_response_;
            deps_.tts.speak(resp);
            return resp;
        }
        case Intent::Help:   return doHelp();
        case Intent::Cancel: {
            std::string r2 = ar ? "تمام." : "Okay.";
            say(r2, UiSeverity::Info);
            return r2;
        }
        case Intent::Unknown:
        default: {
            std::string resp = ar
                ? "معلش، مسمعتش كويس. تقدر تسألني أشرحلك لمبة تحذير، أقولك لو "
                  "خطيرة، أو أدورلك على أقرب ورشة."
                : "Sorry, I didn't catch that. You can ask me to explain a warning "
                  "light, tell you if it's serious, or find the nearest garage.";
            say(resp, UiSeverity::Info);
            return resp;
        }
    }
}

std::string Assistant::doExplain(const std::optional<std::string>& code_slot) {
    std::lock_guard<std::mutex> lock(mtx_);
    const bool ar = (language_ == Language::ArabicEgypt);
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
            std::string resp = ar
                ? "خبر كويس، مفيش أي تحذيرات نشطة دلوقتي."
                : "Good news, I'm not seeing any active warnings right now.";
            say(resp, UiSeverity::Info);
            return resp;
        }
        target = *f;
    }

    focus_code_ = target.code;
    Assessment a = deps_.diagnostics.assess(target, language_);
    std::string resp = composeExplanation(a, target);
    say(resp, toUi(a.severity));
    return resp;
}

std::string Assistant::doAssessSeverity() {
    std::lock_guard<std::mutex> lock(mtx_);
    const bool ar = (language_ == Language::ArabicEgypt);
    std::optional<FaultEvent> target;
    if (focus_code_) {
        auto it = std::find_if(faults_.begin(), faults_.end(),
                               [&](const FaultEvent& f) { return f.code == *focus_code_; });
        if (it != faults_.end()) target = *it;
    }
    if (!target) target = focusFault();
    if (!target) {
        std::string resp = ar
            ? "مفيش حاجة نشطة تقلقك دلوقتي."
            : "There's nothing active to worry about at the moment.";
        say(resp, UiSeverity::Info);
        return resp;
    }
    focus_code_ = target->code;
    Assessment a = deps_.diagnostics.assess(*target, language_);
    std::string resp = composeSeverity(a);
    say(resp, toUi(a.severity));
    return resp;
}

std::string Assistant::doFindService() {
    std::lock_guard<std::mutex> lock(mtx_);
    const bool ar = (language_ == Language::ArabicEgypt);
    if (!deps_.location) {
        std::string resp = ar
            ? "مقدرش أدور على ورش قريبة في النسخة دي لسه، بس بناءً على التحذير "
              "لازم تودّيها تتشاف قريب."
            : "I can't look up nearby garages in this setup yet, but based on the "
              "warning you should have it seen to soon.";
        say(resp, UiSeverity::Info);
        return resp;
    }
    std::string svc = appendService();
    std::string resp = svc.empty()
        ? (ar ? "معرفتش ألاقي محطة خدمة قريبة دلوقتي."
              : "I couldn't find a service station nearby right now.")
        : (ar ? "دي أقرب حاجة ليك." : "Here's what's close by.") + svc;
    say(resp, UiSeverity::Info);
    return resp;
}

std::string Assistant::doListFaults() {
    std::lock_guard<std::mutex> lock(mtx_);
    const bool ar = (language_ == Language::ArabicEgypt);
    if (faults_.empty()) {
        std::string resp = ar
            ? "مفيش أي أعطال دلوقتي. كل حاجة تمام."
            : "I'm not seeing any faults at the moment. Everything looks fine.";
        say(resp, UiSeverity::Info);
        return resp;
    }
    std::ostringstream os;
    if (ar) {
        os << "حاليًا فيه " << faults_.size()
           << (faults_.size() == 1 ? " حاجة واحدة: " : " حاجات: ");
    } else {
        os << "I'm currently aware of " << faults_.size()
           << (faults_.size() == 1 ? " item: " : " items: ");
    }
    // Sort by severity desc for the readout.
    std::vector<std::pair<Severity, std::string>> lines;
    for (const auto& f : faults_) {
        Assessment a = deps_.diagnostics.assess(f, language_);
        std::string tag = (f.source == FaultSource::Predicted)
            ? (ar ? " (متوقع)" : " (predicted)") : "";
        lines.emplace_back(a.severity, a.info.name + tag);
    }
    std::sort(lines.begin(), lines.end(),
              [](auto& x, auto& y) { return x.first > y.first; });
    for (size_t i = 0; i < lines.size(); ++i) {
        os << lines[i].second;
        if (i + 1 < lines.size()) os << "; ";
    }
    os << (ar ? ". اسألني عن أي واحدة فيهم عشان أشرحلك أكتر."
              : ". Ask me about any of them for more detail.");
    std::string resp = os.str();
    say(resp, UiSeverity::Info);
    return resp;
}

std::string Assistant::doHelp() const {
    const bool ar = (language() == Language::ArabicEgypt);
    std::string resp = ar
        ? "أنا مساعد الصيانة بتاعك. تقدر تسألني حاجات زي: إيه اللمبة دي، هل "
          "الموضوع خطير، أقدر أكمل سواقة ولا لأ، أو فين أقرب ورشة. وهقولك "
          "بنفسي لو في حاجة مستعجلة حصلت."
        : "I'm your maintenance assistant. Ask things like: what's this warning "
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
    const bool ar = (language_ == Language::ArabicEgypt);
    switch (s) {
        case Severity::StopNow:
            return ar ? "الموضوع مستعجل. " : "This is urgent. ";
        case Severity::Urgent:
            return ar ? "الموضوع خطير. " : "This is serious. ";
        case Severity::Soon:
            return ar ? "يستحق إنك تتصرف فيه قريب. " : "It's worth acting on soon. ";
        case Severity::Advisory:
            return ar ? "الموضوع بسيط. " : "It's minor. ";
        case Severity::Info:
        default:
            return "";
    }
}

std::string Assistant::composeExplanation(const Assessment& a, const FaultEvent& e) {
    const bool ar = (language_ == Language::ArabicEgypt);
    std::ostringstream os;

    if (e.source == FaultSource::Predicted) {
        os << (ar ? "ده تنبيه استباقي مش عطل نشط دلوقتي. "
                  : "This is a heads-up rather than an active fault. ");
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
            if (ar) {
                s2 << " في محطة خدمة اسمها " << st.name << " على بعد حوالي "
                   << static_cast<int>(st.distance_km + 0.5) << " كيلومتر";
                if (!st.open_now) s2 << "، وممكن تكون مقفولة دلوقتي";
                s2 << ".";
            } else {
                s2 << " There's a service station, " << st.name << ", about "
                   << static_cast<int>(st.distance_km + 0.5) << " kilometres away";
                if (!st.open_now) s2 << ", though it may be closed right now";
                s2 << ".";
            }
            svc = s2.str();
        }
        os << svc;
    }
    return os.str();
}

std::string Assistant::composeSeverity(const Assessment& a) const {
    const bool ar = (language_ == Language::ArabicEgypt);
    std::ostringstream os;
    if (ar) {
        switch (a.severity) {
            case Severity::StopNow:
                os << "لأ — لازم توقف أول ما يبقى آمن. " << a.action;
                break;
            case Severity::Urgent:
                os << "تقدر تسوق بحرص دلوقتي، بس متأجلش الموضوع. " << a.action;
                break;
            case Severity::Soon:
                os << "أيوه، تقدر تكمل سواقة، بس وديها تتشاف قريب. " << a.action;
                break;
            case Severity::Advisory:
            case Severity::Info:
            default:
                os << "أيوه، تمام إنك تكمل سواقة. " << a.action;
                break;
        }
        if (!a.escalation_reason.empty())
            os << " رفعت درجة الخطورة بسبب قراءات الحساسات الحالية.";
        return os.str();
    }

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
    const bool ar = (language_ == Language::ArabicEgypt);
    std::ostringstream os;
    for (size_t i = 0; i < stations.size(); ++i) {
        const auto& st = stations[i];
        if (ar) {
            os << " " << st.name << "، على بعد " << static_cast<int>(st.distance_km + 0.5)
               << " كيلومتر" << (st.open_now ? "" : " (ممكن تكون مقفولة)") << ".";
        } else {
            os << " " << st.name << ", " << static_cast<int>(st.distance_km + 0.5)
               << " kilometres away" << (st.open_now ? "" : " (may be closed)") << ".";
        }
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
