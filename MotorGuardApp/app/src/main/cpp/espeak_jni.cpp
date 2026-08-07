// espeak_jni.cpp — owner D
// Text -> IPA phonemes, the first stage of Piper.
//
// Piper is a VITS model that consumes phoneme IDs, not text. Upstream uses
// piper-phonemize, which is a thin wrapper over espeak-ng; this is that wrapper,
// minus the parts we do not need. The phoneme -> ID mapping and the model itself
// live in Kotlin (PiperTts), because ONNX Runtime is already on the classpath as
// an AAR and there is no reason to build it natively as well.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>

#include "speak_lib.h"

#define TAG "MotorGuardVoice"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
bool g_ready = false;

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

/**
 * @param dataPath directory CONTAINING espeak-ng-data (not the data dir itself).
 *                 espeak-ng appends "/espeak-ng-data" internally.
 */
JNIEXPORT jboolean JNICALL
Java_com_motorguard_ivi_ui_voice_PiperTts_nativeInitEspeak(
        JNIEnv *env, jobject, jstring dataPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ready) return JNI_TRUE;

    const std::string path = jstr(env, dataPath);

    // AUDIO_OUTPUT_SYNCHRONOUS: we never want espeak to touch an audio device.
    // It is used purely as a phonemiser; synthesis is the ONNX model's job.
    const int rate = espeak_Initialize(
        AUDIO_OUTPUT_SYNCHRONOUS, /*buflength*/ 0, path.c_str(), /*options*/ 0);
    if (rate < 0) {
        LOGE("espeak: init failed for data path %s", path.c_str());
        return JNI_FALSE;
    }

    g_ready = true;
    LOGI("espeak: initialised (data=%s)", path.c_str());
    return JNI_TRUE;
}

/**
 * Returns IPA phonemes for [text], with '_' between clauses.
 *
 * espeakPHONEMES_IPA is what Piper voices are trained against; the default
 * espeak notation will silently produce garbage IDs.
 */
JNIEXPORT jstring JNICALL
Java_com_motorguard_ivi_ui_voice_PiperTts_nativePhonemize(
        JNIEnv *env, jobject, jstring text, jstring voice) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ready) {
        LOGE("espeak: phonemize before init");
        return env->NewStringUTF("");
    }

    const std::string v = jstr(env, voice);
    if (espeak_SetVoiceByName(v.empty() ? "en-us" : v.c_str()) != EE_OK) {
        LOGE("espeak: unknown voice %s", v.c_str());
        return env->NewStringUTF("");
    }

    const std::string in = jstr(env, text);
    const char *ptr = in.c_str();

    // 0x02 selects IPA; the low bits are the separator (0 = none).
    const int mode = espeakPHONEMES_IPA | (0 << 8);

    std::string out;
    while (ptr != nullptr) {
        // espeak_TextToPhonemesWithTerminator advances ptr clause by clause and
        // returns a pointer into espeak's own static buffer -- copy immediately.
        const char *ph = espeak_TextToPhonemes(
            reinterpret_cast<const void **>(&ptr), espeakCHARS_UTF8, mode);
        if (ph == nullptr) break;
        if (!out.empty()) out += " ";
        out += ph;
    }

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_motorguard_ivi_ui_voice_PiperTts_nativeReleaseEspeak(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ready) return;
    espeak_Terminate();
    g_ready = false;
}

} // extern "C"