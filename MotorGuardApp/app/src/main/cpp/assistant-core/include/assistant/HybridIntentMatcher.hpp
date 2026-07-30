#pragma once

#include <string>

#include "IntentMatcher.hpp"
#include "LanguageModel.hpp"

namespace assistant {

// Tries the keyword matcher first. Only when it returns Unknown does it spend
// tokens asking a language model to classify the utterance into the *same closed
// set* of intents.
//
// Why this ordering matters on a Pi 5: the keyword path costs microseconds and
// handles the overwhelming majority of real utterances. The model runs on the
// tail. And because the output is a single short label rather than a paragraph,
// even a small model answers in a fraction of the time a spoken sentence needs.
//
// The model is never asked what to *do* — only what the driver *meant*. The code
// slot is still extracted deterministically, not read out of the model's reply.
class HybridIntentMatcher : public IIntentMatcher {
public:
    // `llm` may be null, in which case this behaves exactly like the keyword matcher.
    HybridIntentMatcher(const KeywordIntentMatcher& keywords, ILanguageModel* llm);

    IntentResult match(const std::string& utterance) const override;

    // Counters, so tests (and you, on the Pi) can prove the model is only being
    // consulted on the tail rather than on every utterance.
    int keywordHits() const { return keyword_hits_; }
    int llmCalls() const { return llm_calls_; }

    // Exposed so you can inspect exactly what gets sent to the model.
    static std::string buildPrompt(const std::string& utterance);

private:
    const KeywordIntentMatcher& keywords_;
    ILanguageModel* llm_;
    mutable int keyword_hits_ = 0;
    mutable int llm_calls_ = 0;
};

}  // namespace assistant
