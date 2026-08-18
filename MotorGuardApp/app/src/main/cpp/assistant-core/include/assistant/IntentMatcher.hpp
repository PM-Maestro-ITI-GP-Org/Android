#pragma once

#include <optional>
#include <string>

#include "FaultEvent.hpp"  // Language

namespace assistant {

// What the driver is trying to do. Kept small and closed on purpose: the
// error-explaining feature only needs a handful of intents, and a closed set is
// far easier to handle safely than open-ended conversation.
enum class Intent {
    Unknown,
    ExplainWarning,   // "what's this light", "what does P0217 mean"
    AssessSeverity,   // "is it serious", "can I keep driving"
    FindService,      // "where's the nearest garage"
    RepeatLast,       // "say that again"
    ListFaults,       // "what's wrong with the car"
    Help,             // "what can you do"
    Cancel            // "never mind", "stop"
};

const char* toString(Intent i) noexcept;

// Short uppercase label used when a language model classifies an utterance.
// Round-trips with intentFromLabel().
const char* toLabel(Intent i) noexcept;
std::optional<Intent> intentFromLabel(const std::string& label);

struct IntentResult {
    Intent intent = Intent::Unknown;
    // If the driver spoke an explicit code ("P0217", "P zero two one seven"),
    // it is normalised and captured here. Empty means "the current fault".
    std::optional<std::string> code_slot;
    float confidence = 0.0f;  // 0..1
};

// The seam. Anything that can turn an utterance into an intent implements this.
// [language] is a hint, not a requirement to translate: a matcher that only
// understands English (KeywordIntentMatcher) is free to ignore it.
class IIntentMatcher {
public:
    virtual ~IIntentMatcher() = default;
    virtual IntentResult match(const std::string& utterance,
                                Language language = Language::English) const = 0;
};

// Grammar / keyword based matcher. Deterministic, tiny, English, zero deps.
// Always consulted first: it is free and it cannot hallucinate.
class KeywordIntentMatcher : public IIntentMatcher {
public:
    IntentResult match(const std::string& utterance,
                        Language language = Language::English) const override;
};

// Exposed for reuse: the LLM fallback still extracts spoken diagnostic codes
// with this deterministic parser rather than trusting the model to echo them.
std::optional<std::string> extractFaultCode(const std::string& utterance);

}  // namespace assistant
