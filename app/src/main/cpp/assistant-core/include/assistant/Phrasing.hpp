#pragma once

#include <string>
#include <vector>

#include "FaultEvent.hpp"
#include "LanguageModel.hpp"

namespace assistant {

// Rewrites the *explanation* sentence of a fault. Never the severity, never the
// recommended action — those are not passed in and cannot be returned.
class IPhrasing {
public:
    virtual ~IPhrasing() = default;
    virtual std::string rephrase(const std::string& explanation, Severity sev) = 0;
};

// Default: say exactly what the database says. Zero cost, zero risk.
class PassthroughPhrasing : public IPhrasing {
public:
    std::string rephrase(const std::string& explanation, Severity) override {
        return explanation;
    }
};

struct PhrasingConfig {
    // Never rephrase anything at or above this severity. Two reasons, both good:
    // an urgent driver needs the answer *now* (no generation latency), and the
    // wording of a stop-now message is the last thing to gamble on.
    Severity max_severity = Severity::Soon;

    int  max_tokens = 90;
    // Reject a rewrite whose length is wildly off from the original — a decent
    // proxy for the model having wandered off or truncated mid-thought.
    double min_length_ratio = 0.4;
    double max_length_ratio = 2.2;
};

// Wraps a language model. Every failure mode falls back to the original text,
// which is always safe because the original was written by a human.
class LlmPhrasing : public IPhrasing {
public:
    LlmPhrasing(ILanguageModel& llm, PhrasingConfig cfg = {});

    std::string rephrase(const std::string& explanation, Severity sev) override;

    // Diagnostics: how often we accepted vs fell back. Useful on real hardware.
    int accepted() const { return accepted_; }
    int rejected() const { return rejected_; }
    int skipped() const { return skipped_; }   // too severe to touch

    static std::string buildPrompt(const std::string& explanation);

    // A rewrite is refused if it smuggles in driving advice. The model is told
    // not to; this is what happens when it does anyway.
    static bool containsDirective(const std::string& text);

private:
    ILanguageModel& llm_;
    PhrasingConfig cfg_;
    int accepted_ = 0, rejected_ = 0, skipped_ = 0;
};

}  // namespace assistant
