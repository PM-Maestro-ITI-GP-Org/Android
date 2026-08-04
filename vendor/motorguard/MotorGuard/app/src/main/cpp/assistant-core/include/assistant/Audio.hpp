#pragma once

#include <cstdint>
#include <vector>

// Audio I/O ports. Like the other ports, the core depends only on these; the
// concrete devices (miniaudio on Linux/Pi/Android, or a WAV file for testing)
// are adapters. All audio in this system is 16-bit signed, mono.

namespace assistant {

// Pulls raw PCM from a source (microphone or file). Blocking pull model so the
// VoiceLoop can run a simple read-loop on its own thread.
class IAudioCapture {
public:
    virtual ~IAudioCapture() = default;

    // Open the device/file at the given sample rate (mono). Returns false on error.
    virtual bool start(int sample_rate) = 0;
    virtual void stop() = 0;

    // Fill `out` with up to max_frames samples. Returns the number of samples
    // written. Returns 0 to signal end-of-stream (file exhausted) or that the
    // source has stopped. Blocks until some audio is available.
    virtual size_t read(int16_t* out, size_t max_frames) = 0;

    virtual bool running() const = 0;
};

// Plays 16-bit mono PCM. Used as the audio sink for Piper TTS output.
class IAudioSink {
public:
    virtual ~IAudioSink() = default;
    virtual bool start(int sample_rate) = 0;
    virtual void play(const std::vector<int16_t>& pcm, int sample_rate) = 0;
    virtual void stop() = 0;
};

}  // namespace assistant
