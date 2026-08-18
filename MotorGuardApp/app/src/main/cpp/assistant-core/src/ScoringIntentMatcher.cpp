#include "assistant/ScoringIntentMatcher.hpp"

#include <algorithm>
#include <cctype>
#include <cstring>
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

// Collapses the hamza-on-alef forms and alef maqsura to one base letter each.
// Whisper (and drivers typing/speaking casually) don't consistently pick one
// form -- "إزاي"/"ازاي"/"ازايّ" are the same word -- so an exact substring
// match against table_ar_ would otherwise silently miss a correct hit. Every
// character here is a fixed 2-byte UTF-8 sequence, so a literal byte
// replacement is safe and doesn't need real Unicode handling.
std::string normalizeArabic(std::string s) {
    auto replaceAll = [](std::string& str, const char* from, const char* to) {
        const size_t flen = std::strlen(from);
        size_t pos = 0;
        while ((pos = str.find(from, pos)) != std::string::npos) {
            str.replace(pos, flen, to);
            pos += std::strlen(to);
        }
    };
    replaceAll(s, "\xD8\xA3", "\xD8\xA7");  // أ -> ا
    replaceAll(s, "\xD8\xA5", "\xD8\xA7");  // إ -> ا
    replaceAll(s, "\xD8\xA2", "\xD8\xA7");  // آ -> ا
    replaceAll(s, "\xD9\x89", "\xD9\x8A");  // ى -> ي
    return s;
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

    // Egyptian Arabic signals, in the same spirit as table_ above but matched
    // as raw substrings (scoreAllArabic), never against stemmed tokens: stem()
    // and tokenize() only know Latin suffixes/isalnum, which drop Arabic UTF-8
    // text entirely rather than segmenting it. A short common word (e.g. "قف")
    // risks matching inside an unrelated longer word; that is an accepted
    // trade-off for a small fixed vocabulary, not a concern worth a real
    // Arabic morphological analyser here.
    table_ar_ = {
        {Intent::AssessSeverity, {
            {"خطير", 1.0f}, {"خطورة", 1.0f}, {"خطر", 0.8f}, {"أمان", 0.7f}, {"آمن", 0.8f},
            {"أقدر أكمل", 0.9f}, {"أكمل سواقة", 0.9f}, {"أكمل السواقة", 0.9f},
            {"أوقف", 0.7f}, {"أوقّف", 0.7f}, {"لازم أوقف", 1.0f}, {"أقف", 0.5f},
            {"أطفي", 0.6f}, {"وحش", 0.5f}, {"يستاهل", 0.5f}, {"أستنى", 0.4f},
            {"أوصل البيت", 0.8f}, {"أروح البيت", 0.8f}, {"تمام كده", 0.5f},
            {"هل أقف", 0.9f}, {"محتاج أوقف", 0.9f},
        }},
        {Intent::ExplainWarning, {
            {"لمبة", 1.0f}, {"تحذير", 0.9f}, {"معناها ايه", 0.9f}, {"معناه ايه", 0.9f},
            {"معناها إيه", 0.9f}, {"معناه إيه", 0.9f}, {"ايه ده", 0.6f}, {"إيه ده", 0.6f},
            {"دي ايه", 0.6f}, {"ايه المشكلة", 0.7f}, {"إيه المشكلة", 0.7f},
            {"كود", 0.7f}, {"خطأ", 0.6f}, {"عطل", 0.7f}, {"ايه اللمبة", 0.9f},
            {"إيه اللمبة", 0.9f}, {"لمبة حمرا", 0.9f}, {"لمبة صفرا", 0.9f},
            {"طبلون", 0.8f}, {"وامضة", 0.6f}, {"بتلمع", 0.5f}, {"ايه معنى", 0.8f},
        }},
        {Intent::FindService, {
            {"ورشة", 1.0f}, {"ميكانيكي", 1.0f}, {"أقرب ورشة", 1.2f},
            {"محطة خدمة", 1.0f}, {"صيانة", 0.6f}, {"أصلحها", 0.6f},
            {"أروح فين", 0.8f}, {"فين أقرب", 1.0f}, {"احجز موعد", 0.6f},
            {"أوديها فين", 0.8f}, {"فين ورشة", 1.0f},
        }},
        {Intent::ListFaults, {
            {"مشاكل", 1.0f}, {"أعطال", 1.0f}, {"ايه اللي غلط", 1.0f}, {"إيه اللي غلط", 1.0f},
            {"فيه ايه", 0.7f}, {"فيه إيه", 0.7f}, {"كل حاجة", 0.6f},
            {"حالة العربية", 1.0f}, {"ايه المشاكل", 1.0f}, {"إيه المشاكل", 1.0f},
            {"في حاجة تانية", 0.9f}, {"حاجة تانية", 0.6f},
        }},
        {Intent::RepeatLast, {
            {"قول تاني", 1.0f}, {"قولها تاني", 1.0f}, {"اعد", 0.6f}, {"كرر", 1.0f},
            {"مسمعتش", 0.9f}, {"قول تانى", 1.0f}, {"تاني ايه", 0.6f},
        }},
        {Intent::Help, {
            {"تقدر تعمل ايه", 1.0f}, {"تقدر تعمل إيه", 1.0f}, {"مساعدة", 0.7f},
            {"ازاي تشتغل", 0.9f}, {"إزاي تشتغل", 0.9f}, {"بتعمل ايه", 0.8f}, {"بتعمل إيه", 0.8f},
        }},
        {Intent::Cancel, {
            {"خلاص", 0.9f}, {"بلاش", 0.9f}, {"الغاء", 1.0f}, {"إلغاء", 1.0f},
            {"اسكت", 1.0f}, {"بطل كلام", 1.0f}, {"مش عايز", 0.6f},
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

std::vector<ScoringIntentMatcher::Score>
ScoringIntentMatcher::scoreAllArabic(const std::string& utterance) const {
    // No tolower()/stem() here on purpose: both are byte-wise and only know
    // ASCII, so they would either corrupt or silently pass through Arabic
    // UTF-8 text depending on the byte. normalizeArabic() is the one
    // normalization step that does apply -- see its comment.
    const std::string norm = normalizeArabic(utterance);
    std::vector<Score> out;
    for (const auto& entry : table_ar_) {
        float score = 0.0f;
        for (const auto& sig : entry.signals) {
            if (norm.find(normalizeArabic(sig.token)) != std::string::npos) score += sig.weight;
        }
        out.push_back({entry.intent, score});
    }
    std::sort(out.begin(), out.end(),
              [](const Score& a, const Score& b) { return a.score > b.score; });
    return out;
}

IntentResult ScoringIntentMatcher::match(const std::string& utterance, Language /*language*/) const {
    IntentResult r;
    if (utterance.empty()) return r;

    r.code_slot = extractFaultCode(utterance);

    // An explicitly spoken fault code is near-decisive on its own, regardless
    // of language: DTC codes are read out in Latin letters/digits either way.
    if (r.code_slot) {
        r.intent = Intent::ExplainWarning;
        r.confidence = 0.9f;
        return r;
    }

    // Score against both vocabularies rather than picking one by `language`:
    // the reply language and the language the driver actually spoke in are
    // independent (VoiceEngine.setLanguage forces English replies regardless
    // of STT input language), and Whisper's Egyptian model can produce
    // code-switched text besides. Taking the best of both means "لمبة" and
    // "explain" both work no matter what `language` the reply is composed
    // in.
    auto scores = scoreAll(utterance);
    const auto scoresAr = scoreAllArabic(utterance);
    for (auto& s : scores) {
        auto it = std::find_if(scoresAr.begin(), scoresAr.end(),
                                [&](const Score& a) { return a.intent == s.intent; });
        if (it != scoresAr.end() && it->score > s.score) s.score = it->score;
    }
    std::sort(scores.begin(), scores.end(),
              [](const Score& a, const Score& b) { return a.score > b.score; });

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
