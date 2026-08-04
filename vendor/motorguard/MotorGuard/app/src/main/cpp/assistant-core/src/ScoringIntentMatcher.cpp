#include "assistant/ScoringIntentMatcher.hpp"

#include <algorithm>
#include <cctype>
#include <map>

#include "assistant/Log.hpp"

namespace assistant {

namespace {
constexpr const char* TAG = "intent";

std::string lower(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

bool endsWith(const std::string& s, const char* suf) {
    const size_t n = std::string(suf).size();
    return s.size() > n && s.compare(s.size() - n, n, suf) == 0;
}
}  // namespace

std::string stem(const std::string& word) {
    std::string w = lower(word);
    // Order matters: longest suffixes first.
    if (endsWith(w, "ing") && w.size() > 5) {
        w = w.substr(0, w.size() - 3);
        // "driving" -> "driv" -> restore a plausible stem
        if (!w.empty() && w.back() != 'e') { /* leave as-is; signals use the same stem */ }
    } else if (endsWith(w, "ies") && w.size() > 4) {
        w = w.substr(0, w.size() - 3) + "y";
    } else if (endsWith(w, "es") && w.size() > 4) {
        w = w.substr(0, w.size() - 2);
    } else if (endsWith(w, "s") && !endsWith(w, "ss") && w.size() > 3) {
        w = w.substr(0, w.size() - 1);
    } else if (endsWith(w, "ed") && w.size() > 4) {
        w = w.substr(0, w.size() - 2);
    }
    return w;
}

std::vector<std::string> tokenize(const std::string& text) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : text) {
        if (std::isalnum(static_cast<unsigned char>(c))) {
            cur += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        } else if (!cur.empty()) {
            out.push_back(cur);
            cur.clear();
        }
    }
    if (!cur.empty()) out.push_back(cur);
    return out;
}

ScoringIntentMatcher::ScoringIntentMatcher() {
    // Signals are stems. Weight reflects how strongly the word points at the
    // intent on its own: 1.0 is near-decisive, 0.3 is a weak hint that only
    // matters when it stacks with others.
    //
    // Tuning tip: if the assistant keeps mishearing an intent, add signals here
    // rather than rewriting logic. This table IS the "understanding".

    table_ = {
        {Intent::AssessSeverity, {
            // Direct
            {"seriou", 1.0f}, {"danger", 1.0f}, {"bad", 0.7f}, {"urgent", 0.9f},
            {"safe", 0.9f}, {"risky", 0.9f}, {"worri", 0.7f}, {"worry", 0.7f},
            // "can I keep driving" and its many disguises
            {"driv", 0.6f}, {"keep", 0.4f}, {"continu", 0.5f}, {"carry", 0.4f},
            {"home", 0.5f}, {"okay", 0.5f}, {"ok", 0.4f}, {"alright", 0.5f},
            {"fine", 0.4f}, {"stop", 0.6f}, {"pull", 0.6f}, {"over", 0.3f},
            {"should", 0.4f}, {"need", 0.3f}, {"worse", 0.6f}, {"harm", 0.7f},
            {"damag", 0.6f}, {"make it", 0.6f}, {"get home", 0.9f},
            {"how bad", 1.0f}, {"is it ok", 0.9f},
        }},
        {Intent::ExplainWarning, {
            {"warn", 1.0f}, {"light", 0.9f}, {"error", 0.9f}, {"fault", 0.9f},
            {"code", 0.7f}, {"mean", 0.9f}, {"symbol", 0.9f}, {"icon", 0.9f},
            {"dash", 0.8f}, {"cluster", 0.8f}, {"orang", 0.8f}, {"amber", 0.8f},
            {"red", 0.6f}, {"yellow", 0.7f}, {"lit", 0.7f}, {"flash", 0.7f},
            {"blink", 0.7f}, {"came on", 0.8f}, {"lit up", 0.9f},
            {"what is thi", 0.8f}, {"what thi", 0.7f}, {"thing", 0.3f},
            {"explain", 0.9f}, {"tell me about", 0.7f}, {"why", 0.4f},
        }},
        {Intent::FindService, {
            {"garag", 1.0f}, {"mechanic", 1.0f}, {"workshop", 1.0f},
            {"repair", 0.9f}, {"servic", 0.8f}, {"station", 0.7f},
            {"nearest", 0.9f}, {"near", 0.7f}, {"nearby", 0.9f}, {"close", 0.5f},
            {"where", 0.7f}, {"fix", 0.7f}, {"look at", 0.6f}, {"looked at", 0.8f},
            {"somewher", 0.6f}, {"place", 0.5f}, {"book", 0.5f}, {"appointment", 0.7f},
            {"take it", 0.5f}, {"drop it", 0.5f},
        }},
        {Intent::ListFaults, {
            {"wrong", 1.2f}, {"problem", 1.0f}, {"issu", 1.0f}, {"everyth", 1.0f},
            {"list", 1.0f}, {"status", 0.9f}, {"health", 0.8f},
            {"anything else", 1.2f}, {"anythin els", 1.0f}, {"what else", 1.2f},
            {"else", 0.7f}, {"other", 0.5f}, {"all", 0.4f}, {"any", 0.3f},
            {"know about", 0.6f}, {"should i know", 0.9f},
            {"how is the car", 1.0f}, {"with the car", 0.6f},
        }},
        {Intent::RepeatLast, {
            {"repeat", 1.0f}, {"again", 0.9f}, {"say that", 0.9f},
            {"didn't catch", 0.9f}, {"didnt catch", 0.9f}, {"pardon", 0.9f},
            {"what did you say", 1.0f}, {"sorry", 0.4f},
        }},
        {Intent::Help, {
            {"help", 0.8f}, {"what can you", 1.0f}, {"what do you do", 1.0f},
            {"how do you work", 0.9f}, {"abl", 0.5f}, {"command", 0.6f},
        }},
        {Intent::Cancel, {
            {"never mind", 1.0f}, {"nevermind", 1.0f}, {"cancel", 1.0f},
            {"forget it", 1.0f}, {"stop talk", 1.0f}, {"shut up", 1.0f},
            {"quiet", 0.8f}, {"nothing", 0.6f},
        }},
    };
}

std::vector<ScoringIntentMatcher::Score>
ScoringIntentMatcher::scoreAll(const std::string& utterance) const {
    const std::string raw = lower(utterance);
    const std::vector<std::string> words = tokenize(utterance);

    // Stem every word once.
    std::vector<std::string> stems;
    stems.reserve(words.size());
    for (const auto& w : words) stems.push_back(stem(w));

    // Negation flips the meaning of severity-ish statements. "can I NOT keep
    // driving" and "is it NOT serious" shouldn't score the same as the positive
    // form — but they're still asking about severity, so we don't suppress the
    // intent, we just avoid over-boosting it.
    bool negated = false;
    for (const auto& w : words)
        if (w == "not" || w == "dont" || w == "don" || w == "cant" || w == "shouldnt")
            negated = true;

    std::vector<Score> out;
    for (const auto& entry : table_) {
        float score = 0.0f;
        for (const auto& sig : entry.signals) {
            if (sig.token.find(' ') != std::string::npos) {
                // Multi-word signal: substring match on the raw text.
                if (raw.find(sig.token) != std::string::npos) score += sig.weight;
            } else {
                // Single-word signal: match against stems (so driving/drives/drive all hit).
                for (const auto& st : stems) {
                    if (st == sig.token || st.rfind(sig.token, 0) == 0) {
                        score += sig.weight;
                        break;  // count each signal at most once
                    }
                }
            }
        }
        if (negated && entry.intent == Intent::AssessSeverity) score *= 0.9f;
        out.push_back({entry.intent, score});
    }

    std::sort(out.begin(), out.end(),
              [](const Score& a, const Score& b) { return a.score > b.score; });
    return out;
}

IntentResult ScoringIntentMatcher::match(const std::string& utterance) const {
    IntentResult r;
    if (utterance.empty()) return r;

    r.code_slot = extractFaultCode(utterance);

    // An explicitly spoken fault code is near-decisive on its own.
    if (r.code_slot) {
        r.intent = Intent::ExplainWarning;
        r.confidence = 0.9f;
        return r;
    }

    const auto scores = scoreAll(utterance);
    if (scores.empty() || scores[0].score < threshold_) {
        LOG_D(TAG, "'%s' -> Unknown (best %.2f < %.2f)", utterance.c_str(),
              scores.empty() ? 0.0f : scores[0].score, threshold_);
        return r;  // Unknown: better to admit confusion than act on a guess.
    }

    r.intent = scores[0].intent;
    // Confidence reflects both absolute score and the margin over the runner-up:
    // a clear winner is more trustworthy than a photo finish.
    const float best = scores[0].score;
    const float second = scores.size() > 1 ? scores[1].score : 0.0f;
    const float margin = (best > 0.0f) ? (best - second) / best : 0.0f;
    r.confidence = std::min(0.95f, 0.5f + 0.3f * margin + 0.15f * std::min(best / 2.0f, 1.0f));

    LOG_D(TAG, "'%s' -> %s (score %.2f, margin %.2f)", utterance.c_str(),
          toString(r.intent), best, margin);
    return r;
}

}  // namespace assistant
