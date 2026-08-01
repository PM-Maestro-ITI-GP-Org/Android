#pragma once

// ---------------------------------------------------------------------------
// Ports: the abstract boundary between the portable core and the platform.
//
// The core (DiagnosticsEngine, IntentMatcher, Assistant) depends ONLY on these
// interfaces. Android, Linux, or a test harness each supply concrete adapters.
// Moving to a new platform means writing new adapters here, not touching the
// brain. Every interface is pure-virtual with a virtual destructor.
// ---------------------------------------------------------------------------

#include <functional>
#include <string>
#include <vector>

#include "FaultEvent.hpp"

namespace assistant {

// --- Vehicle data --------------------------------------------------------
// Delivers fault events from the cluster / predictive-maintenance system.
// subscribe() is push (event-driven); activeFaults() is a pull snapshot.
class IVehicleData {
public:
    using Callback = std::function<void(const FaultEvent&)>;
    virtual ~IVehicleData() = default;

    virtual void subscribe(Callback cb) = 0;
    virtual std::vector<FaultEvent> activeFaults() = 0;

    // Begin producing events (open the socket, start the replay timer, ...).
    virtual void start() = 0;
    virtual void stop() = 0;
};

// --- Speech to text ------------------------------------------------------
// Whisper.cpp, Vosk, or a console stand-in all implement this. transcribe()
// takes a finished utterance (16 kHz mono PCM) and returns recognised text.
class ISpeechToText {
public:
    virtual ~ISpeechToText() = default;

    // pcm16: signed 16-bit samples, mono, 16 kHz. Returns lowercase-ish text,
    // empty string if nothing was recognised.
    virtual std::string transcribe(const std::vector<int16_t>& pcm16) = 0;

    // Text-based fast path for development / console adapters that already have
    // text. Real audio adapters just ignore this. Default: not supported.
    virtual bool acceptsText() const { return false; }
    virtual std::string transcribeText(const std::string& text) { return text; }
};

// --- Text to speech ------------------------------------------------------
// Piper, espeak, or a console stand-in. speak() should block until the phrase
// has been rendered/queued; the core calls it from its own thread.
class ITextToSpeech {
public:
    virtual ~ITextToSpeech() = default;
    virtual void speak(const std::string& text) = 0;
};

// --- Location / points of interest --------------------------------------
// Supplies nearby service stations for the "there's a repair shop nearby"
// recommendation. Offline builds back this with a local POI dataset.
struct ServiceStation {
    std::string name;
    std::string address;
    double      distance_km = 0.0;
    bool        open_now = true;
};

class ILocationProvider {
public:
    virtual ~ILocationProvider() = default;
    // Nearest service stations, nearest first. max is an upper bound.
    virtual std::vector<ServiceStation> nearestServiceStations(int max = 3) = 0;
    virtual bool hasFix() const = 0;   // is a location currently known?
};

// --- UI bridge -----------------------------------------------------------
// Optional channel to the IVI / cluster surface. The error-explaining flow is
// voice-only, so a no-op implementation is fine to start; this is the seam the
// later IVI-assist work plugs into.
enum class UiSeverity { Info, Warning, Critical };

class IUiBridge {
public:
    virtual ~IUiBridge() = default;
    virtual void showMessage(const std::string& text, UiSeverity sev) = 0;
    virtual void clear() = 0;
};

}  // namespace assistant
