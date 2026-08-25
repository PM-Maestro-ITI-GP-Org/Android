// ---------------------------------------------------------------------------
// JNI bridge: com.motorguard.ivi.ui.voice.VoiceEngine  <->  assistant-core (C++)
//
// The whole reasoning layer (fault lookup, safety rules, intent scoring, reply
// composition) is portable C++ and lives in assistant-core/. Kotlin owns only
// the platform edges: microphone, STT, TTS and the overlay UI.
//
// Nothing here decides *what the driver should do* on its own — that verdict is
// produced by DiagnosticsEngine's rules, which may only ever raise severity.
// ---------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>

#include "seed_data.h"

#include "assistant/Assistant.hpp"
#include "assistant/DiagnosticsEngine.hpp"
#include "assistant/ScoringIntentMatcher.hpp"
#include "assistant/Ports.hpp"
#include "assistant-core/adapters/location/StaticLocationProvider.hpp"

using namespace assistant;

#define TAG "MotorGuardVoice"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Captures what the core wants said, instead of speaking it. Kotlin reads the
// text back and hands it to Android's TextToSpeech, so the platform owns audio.
class CapturingTts : public ITextToSpeech {
public:
    void speak(const std::string& text) override { last = text; ++count; }
    std::string last;
    int count = 0;
};

// Faults are pushed in from Kotlin (eventually from CarDataRepository / VHAL).
// This is the IVehicleData port; on the Pi it will be fed by real CAN data.
class PushVehicleData : public IVehicleData {
public:
    void subscribe(Callback cb) override { cb_ = std::move(cb); }
    std::vector<FaultEvent> activeFaults() override { return active_; }
    void start() override {}
    void stop() override {}

    void push(const FaultEvent& e) {
        auto it = std::find_if(active_.begin(), active_.end(),
                               [&](const FaultEvent& f) { return f.code == e.code; });
        if (it != active_.end()) *it = e; else active_.push_back(e);
        if (cb_) cb_(e);
    }
    void clear() { active_.clear(); }

private:
    Callback cb_;
    std::vector<FaultEvent> active_;
};

struct Core {
    DiagnosticsEngine            engine;
    ScoringIntentMatcher         intents;
    CapturingTts                 tts;
    PushVehicleData              vehicle;
    adapters::StaticLocationProvider location;
    std::unique_ptr<Assistant>   assistant;
    bool ready = false;
};

std::unique_ptr<Core> g_core;
std::mutex g_mutex;

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_motorguard_ivi_ui_voice_VoiceEngine_nativeInit(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_core && g_core->ready) return JNI_TRUE;

    auto core = std::make_unique<Core>();
    if (!core->engine.open(":memory:")) {
        LOGE("could not open in-memory fault database");
        return JNI_FALSE;
    }
    if (!core->engine.execScript(seed::kDtcSeedSql)) {
        LOGE("could not seed fault database");
        return JNI_FALSE;
    }
    installDefaultRules(core->engine);

    AssistantDeps deps{core->engine, core->intents, core->tts,
                       core->vehicle, &core->location, nullptr};
    core->assistant = std::make_unique<Assistant>(deps);
    core->assistant->start();
    core->ready = true;

    LOGI("core ready — %d fault definitions loaded", core->engine.faultCount());
    g_core = std::move(core);
    return JNI_TRUE;
}

/** Number of fault definitions in the embedded database (sanity check). */
JNIEXPORT jint JNICALL
Java_com_motorguard_ivi_ui_voice_VoiceEngine_nativeFaultCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_core && g_core->ready) ? g_core->engine.faultCount() : 0;
}

/**
 * Hand a recognised utterance to the core. Returns the reply text to display and
 * speak. Empty string means the core produced nothing.
 */
JNIEXPORT jstring JNICALL
Java_com_motorguard_ivi_ui_voice_VoiceEngine_nativeHandle(
        JNIEnv* env, jobject, jstring jutterance) {
    const std::string utterance = jstr(env, jutterance);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_core || !g_core->ready || utterance.empty())
        return env->NewStringUTF("");

    std::string reply;
    try {
        reply = g_core->assistant->handleUtterance(utterance);
    } catch (const std::exception& ex) {
        LOGE("handleUtterance threw: %s", ex.what());
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(reply.c_str());
}

/**
 * Push a fault in from the vehicle layer.
 *
 * @param code        a cluster code: "E-21" electrical, "E-31" mechanical,
 *                    "E-01" raised but unclassified
 * @param predicted   true for a predictive-maintenance forecast, false for a live DTC
 * @param sensorKey   freeze-frame key, e.g. "coolant_temp_c" ("" for none)
 * @param sensorValue value for that key
 * @return the proactive announcement if the fault was urgent enough to speak up
 *         about, otherwise an empty string.
 */
JNIEXPORT jstring JNICALL
Java_com_motorguard_ivi_ui_voice_VoiceEngine_nativePushFault(
        JNIEnv* env, jobject, jstring jcode, jboolean predicted,
        jstring jsensorKey, jdouble sensorValue) {
    const std::string code = jstr(env, jcode);
    const std::string key  = jstr(env, jsensorKey);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_core || !g_core->ready || code.empty())
        return env->NewStringUTF("");

    FaultEvent e;
    e.code = code;
    e.source = predicted ? FaultSource::Predicted : FaultSource::ActiveDtc;
    if (!key.empty()) e.freeze_frame[key] = sensorValue;

    const int before = g_core->tts.count;
    g_core->vehicle.push(e);          // Assistant::onFault runs the safety rules
    const bool announced = g_core->tts.count > before;
    return env->NewStringUTF(announced ? g_core->tts.last.c_str() : "");
}

/** Drop all known faults (used by tests / demo reset). */
JNIEXPORT void JNICALL
Java_com_motorguard_ivi_ui_voice_VoiceEngine_nativeClearFaults(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_core && g_core->ready) g_core->vehicle.clear();
}

}  // extern "C"
