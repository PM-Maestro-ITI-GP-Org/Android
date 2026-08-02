#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "Assistant.hpp"
#include "Audio.hpp"
#include "Ports.hpp"
#include "Vad.hpp"

namespace assistant {

// Drives the spoken interaction: pulls PCM from an IAudioCapture, segments it
// into utterances with the VAD, transcribes each with ISpeechToText, and passes
// the text to the Assistant. The Assistant speaks its own replies through its
// TTS dependency, so this loop doesn't touch playback.
//
// Everything here is portable: swap the capture adapter (mic vs WAV) and the STT
// adapter (whisper vs mock) without changing this class. Runs on the caller's
// thread until stop() is called or the capture source ends.
class VoiceLoop {
public:
    VoiceLoop(IAudioCapture& capture, ISpeechToText& stt, Assistant& assistant,
              VadConfig vad = {});

    // Optional hooks for UI / logging.
    std::function<void()>                    on_listening;   // mic opened
    std::function<void(const std::string&)>  on_transcript;  // recognised text
    // Raw audio of each detected utterance, before transcription. Useful for
    // building a benchmark set of real recordings from your own cabin.
    std::function<void(const std::vector<int16_t>&, int sample_rate)> on_utterance_audio;

    // Blocking. Returns when the capture source ends or stop() is called.
    void run();
    void stop();

    // Wall-clock milliseconds the last transcription took, and the real-time
    // factor (stt_ms / audio_ms). RTF < 1.0 means faster than real time.
    double lastSttMs() const { return last_stt_ms_; }
    double lastRtf() const { return last_rtf_; }

private:
    void handleUtterance(std::vector<int16_t>&& pcm);

    IAudioCapture&     capture_;
    ISpeechToText&     stt_;
    Assistant&         assistant_;
    UtteranceSegmenter segmenter_;
    std::atomic<bool>  running_{false};
    int                sample_rate_;
    double             last_stt_ms_ = 0.0;
    double             last_rtf_ = 0.0;
};

}  // namespace assistant
