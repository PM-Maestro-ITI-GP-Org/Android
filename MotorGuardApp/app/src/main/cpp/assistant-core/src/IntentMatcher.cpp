#include "assistant/IntentMatcher.hpp"

#include <array>
#include <cctype>
#include <regex>
#include <string>
#include <vector>

namespace assistant {

const char* toString(Intent i) noexcept {
    switch (i) {
        case Intent::Unknown:        return "Unknown";
        case Intent::ExplainWarning: return "ExplainWarning";
        case Intent::AssessSeverity: return "AssessSeverity";
        case Intent::FindService:    return "FindService";
        case Intent::RepeatLast:     return "RepeatLast";
        case Intent::ListFaults:     return "ListFaults";
        case Intent::Help:           return "Help";
        case Intent::Cancel:         return "Cancel";
    }
    return "?";
}

const char* toLabel(Intent i) noexcept {
    switch (i) {
        case Intent::ExplainWarning: return "EXPLAIN";
        case Intent::AssessSeverity: return "SEVERITY";
        case Intent::FindService:    return "SERVICE";
        case Intent::RepeatLast:     return "REPEAT";
        case Intent::ListFaults:     return "LIST";
        case Intent::Help:           return "HELP";
        case Intent::Cancel:         return "CANCEL";
        case Intent::Unknown:        return "UNKNOWN";
    }
    return "UNKNOWN";
}

std::optional<Intent> intentFromLabel(const std::string& label) {
    static const std::pair<const char*, Intent> kMap[] = {
        {"EXPLAIN",  Intent::ExplainWarning},
        {"SEVERITY", Intent::AssessSeverity},
        {"SERVICE",  Intent::FindService},
        {"REPEAT",   Intent::RepeatLast},
        {"LIST",     Intent::ListFaults},
        {"HELP",     Intent::Help},
        {"CANCEL",   Intent::Cancel},
    };
    for (const auto& [text, intent] : kMap)
        if (label.find(text) != std::string::npos) return intent;
    return std::nullopt;
}

namespace {

std::string lower(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

// Try to pull a diagnostic code out of the utterance. Handles both the written
// form ("p0217") and a naive spoken form ("p zero two one seven"). Returns an
// uppercase normalised code, or nullopt.
std::optional<std::string> extractCodeImpl(const std::string& lowered) {
    // Written form: a letter P/B/C/U followed by 4 hex-ish digits.
    static const std::regex kWritten(R"(\b([pbcu])\s*([0-9a-f])\s*([0-9a-f])\s*([0-9a-f])\s*([0-9a-f])\b)",
                                     std::regex::icase);
    std::smatch m;
    if (std::regex_search(lowered, m, kWritten)) {
        std::string code = m[1].str() + m[2].str() + m[3].str() + m[4].str() + m[5].str();
        for (char& c : code) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
        return code;
    }

    // Spoken form: letter word/letter then number words.
    static const std::array<std::pair<const char*, char>, 4> kLetters{{
        {"p", 'P'}, {"b", 'B'}, {"c", 'C'}, {"u", 'U'}}};
    static const std::array<std::pair<const char*, char>, 10> kDigits{{
        {"zero", '0'}, {"one", '1'}, {"two", '2'}, {"three", '3'}, {"four", '4'},
        {"five", '5'}, {"six", '6'}, {"seven", '7'}, {"eight", '8'}, {"nine", '9'}}};

    std::vector<std::string> tok;
    std::string cur;
    for (char c : lowered) {
        if (std::isalnum(static_cast<unsigned char>(c))) cur += c;
        else if (!cur.empty()) { tok.push_back(cur); cur.clear(); }
    }
    if (!cur.empty()) tok.push_back(cur);

    for (size_t i = 0; i < tok.size(); ++i) {
        char letter = 0;
        for (auto& [w, ch] : kLetters) if (tok[i] == w) { letter = ch; break; }
        if (!letter) continue;
        std::string digits;
        for (size_t j = i + 1; j < tok.size() && digits.size() < 4; ++j) {
            char d = 0;
            for (auto& [w, ch] : kDigits) if (tok[j] == w) { d = ch; break; }
            if (tok[j].size() == 1 && std::isdigit(static_cast<unsigned char>(tok[j][0])))
                d = tok[j][0];
            if (!d) break;
            digits += d;
        }
        if (digits.size() == 4) return std::string(1, letter) + digits;
    }
    return std::nullopt;
}

bool containsAny(const std::string& s, std::initializer_list<const char*> words) {
    for (const char* w : words)
        if (s.find(w) != std::string::npos) return true;
    return false;
}

}  // namespace

IntentResult KeywordIntentMatcher::match(const std::string& utterance, Language) const {
    IntentResult r;
    const std::string s = lower(utterance);
    if (s.empty()) return r;

    r.code_slot = extractCodeImpl(s);

    // Order matters: check the most specific / safety-relevant intents first.

    if (containsAny(s, {"never mind", "nevermind", "cancel", "stop talking", "forget it"})) {
        r.intent = Intent::Cancel; r.confidence = 0.9f; return r;
    }
    if (containsAny(s, {"say that again", "repeat", "again please", "what did you say"})) {
        r.intent = Intent::RepeatLast; r.confidence = 0.9f; return r;
    }
    if (containsAny(s, {"can i keep driving", "can i drive", "is it safe", "is it serious",
                        "should i stop", "should i pull over", "how bad", "dangerous",
                        "do i need to stop"})) {
        r.intent = Intent::AssessSeverity; r.confidence = 0.85f; return r;
    }
    if (containsAny(s, {"nearest", "repair", "garage", "mechanic", "service station",
                        "workshop", "where can i", "fix it", "somewhere to"})) {
        r.intent = Intent::FindService; r.confidence = 0.85f; return r;
    }
    if (containsAny(s, {"what's wrong", "whats wrong", "any problems", "list", "everything",
                        "all the", "what faults", "what warnings"})) {
        r.intent = Intent::ListFaults; r.confidence = 0.8f; return r;
    }
    if (containsAny(s, {"what can you do", "help", "how do you work", "what do you do"})) {
        r.intent = Intent::Help; r.confidence = 0.8f; return r;
    }
    // Broadest bucket last: explaining a warning / light / code.
    if (r.code_slot ||
        containsAny(s, {"what's this", "whats this", "what is this", "what does", "warning",
                        "light", "error", "cluster", "dashboard", "mean", "means",
                        "why is", "orange", "red light", "symbol", "icon"})) {
        r.intent = Intent::ExplainWarning; r.confidence = r.code_slot ? 0.9f : 0.7f; return r;
    }

    r.intent = Intent::Unknown;
    r.confidence = 0.0f;
    return r;
}

std::optional<std::string> extractFaultCode(const std::string& utterance) {
    return extractCodeImpl(lower(utterance));
}

}  // namespace assistant
