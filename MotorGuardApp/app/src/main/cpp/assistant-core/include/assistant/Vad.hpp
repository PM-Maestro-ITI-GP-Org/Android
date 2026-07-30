#pragma once

#include <cstdint>
#include <deque>
#include <functional>
#include <vector>

namespace assistant {

struct VadConfig {
    int   sample_rate      = 16000;
    int   window_ms        = 20;    // analysis window size
    // Speech is detected when window RMS exceeds max(abs_threshold,
    // noise_floor * rel_factor). Tune abs_threshold to your mic gain.
    float abs_threshold    = 300.0f;  // ~ -40 dBFS on 16-bit
    float rel_factor       = 3.0f;
    int   min_speech_ms    = 120;   // confirm speech faster so the onset isn't lost
    int   hangover_ms      = 800;   // trailing silence that ends an utterance
    int   pre_roll_ms      = 600;   // generous: keeps the run-up so first words survive
    int   max_utterance_ms = 12000; // hard cap so it can't run forever
};

// Streaming utterance segmenter. Feed it PCM as it arrives; it fires
// on_utterance once per complete spoken segment (pre-roll + speech + trimmed to
// the hangover boundary). Portable, dependency-free, unit-testable on synthetic
// audio. A better VAD (WebRTC / Silero) can replace this behind the same API.
class UtteranceSegmenter {
public:
    explicit UtteranceSegmenter(VadConfig cfg);

    using UtteranceCallback = std::function<void(std::vector<int16_t>&&)>;
    void setCallback(UtteranceCallback cb) { on_utterance_ = std::move(cb); }

    // Feed samples. May fire the callback zero or more times.
    void feed(const int16_t* data, size_t n);

    // If speech is currently in progress, force-emit it (e.g. on shutdown).
    void flush();

    void reset();

    bool inSpeech() const { return in_speech_; }

private:
    void processWindow(const int16_t* win, size_t n);
    void emit();

    VadConfig cfg_;
    size_t window_samples_;
    size_t min_speech_windows_;
    size_t hangover_windows_;
    size_t pre_roll_samples_;
    size_t max_utterance_samples_;

    std::vector<int16_t> pending_;   // samples not yet grouped into a window
    std::deque<int16_t>  pre_roll_;  // rolling buffer before onset

    bool   in_speech_ = false;
    size_t speech_run_ = 0;          // consecutive speech windows at onset
    size_t silence_run_ = 0;         // consecutive silence windows during speech
    float  noise_floor_ = 0.0f;      // adaptive
    std::vector<int16_t> current_;   // accumulating utterance

    UtteranceCallback on_utterance_;
};

// Utility: root-mean-square of a PCM window.
float rms(const int16_t* data, size_t n);

}  // namespace assistant
