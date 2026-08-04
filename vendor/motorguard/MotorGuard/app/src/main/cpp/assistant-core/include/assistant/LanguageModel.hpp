#pragma once

#include <string>

namespace assistant {

// A text-in / text-out language model. Deliberately minimal: the core never
// learns whether this is llama.cpp, a remote API, or a test fake.
//
// NOTE ON SAFETY: nothing in this interface can return a Severity or an action.
// That is intentional. The model can influence *wording* and *classification*
// only; the recommendation the driver acts on is produced by DiagnosticsEngine's
// rules. A model that cannot express "keep driving" as a verdict cannot issue one.
class ILanguageModel {
public:
    virtual ~ILanguageModel() = default;

    // Greedy, deterministic completion of `prompt`, capped at max_tokens.
    // Returns an empty string on any failure — callers must treat "" as
    // "the model gave me nothing, fall back".
    virtual std::string complete(const std::string& prompt, int max_tokens) = 0;

    virtual bool ready() const = 0;
};

}  // namespace assistant
