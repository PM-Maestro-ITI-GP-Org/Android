#pragma once

#include <string>
#include <vector>

#include "IntentMatcher.hpp"

namespace assistant {

// A matcher that scores rather than pattern-matches.
//
// The keyword matcher asks "does this phrase appear?" — which is why a driver who
// rambles gets Unknown. This one asks "how much evidence is there for each
// intent?" Every intent owns a set of weighted signals; an utterance accumulates
// points; the best-scoring intent wins if it clears a threshold. Word order and
// filler words stop mattering:
//
//   "am I okay to get home in this thing"        -> okay + get home   -> AssessSeverity
//   "there's an orange thing lit up on the dash" -> orange + lit + dash -> ExplainWarning
//   "somewhere I can get this looked at"         -> somewhere + looked at -> FindService
//
// No model, no training data, no gigabytes of RAM. Deterministic and instant,
// which also means it can never hallucinate an intent that isn't in the set.
class ScoringIntentMatcher : public IIntentMatcher {
public:
    struct Signal {
        std::string token;  // matched against stemmed words, or as a substring for phrases
        float weight;       // how strongly this points at the intent
    };

    ScoringIntentMatcher();

    IntentResult match(const std::string& utterance) const override;

    // Minimum score for a confident answer. Below this we return Unknown rather
    // than guess — a wrong action is worse than admitting we didn't understand.
    void setThreshold(float t) { threshold_ = t; }
    float threshold() const { return threshold_; }

    // Exposed for testing / tuning: the score each intent received.
    struct Score { Intent intent; float score; };
    std::vector<Score> scoreAll(const std::string& utterance) const;

private:
    struct IntentSignals {
        Intent intent;
        std::vector<Signal> signals;
    };
    std::vector<IntentSignals> table_;
    float threshold_ = 1.0f;
};

// Crude English stemmer: strips common suffixes so "driving"/"drives"/"drive"
// collapse to one signal. Not linguistically rigorous — deliberately simple and
// predictable, which matters more here than correctness on rare words.
std::string stem(const std::string& word);

// Splits into lowercase word tokens, dropping punctuation.
std::vector<std::string> tokenize(const std::string& text);

}  // namespace assistant
