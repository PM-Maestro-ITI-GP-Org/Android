// ---------------------------------------------------------------------------
// JNI bridge: com.motorguard.ivi.data.vehicle.someip.MotorLinkNative <-> MotorLink
//
// Nothing is decided here. Events cross as six primitives and captures as one
// float array; the severity mapping, the freshness state machine and every
// sentence the user ever reads live in Kotlin, where they are testable without
// a device. The rule this file follows is that the boundary carries data, not
// judgement.
// ---------------------------------------------------------------------------
#include <jni.h>

#include <android/log.h>

#include <memory>
#include <string>

#include "motorguard/someip/motor_link.h"

#define TAG "MotorGuardLink"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using motorguard::someip::CaptureHeader;
using motorguard::someip::LinkState;
using motorguard::someip::MotorEvent;
using motorguard::someip::MotorLink;
using motorguard::someip::MotorLinkConfig;

namespace {

JavaVM* gVm = nullptr;

std::string toStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Owns the link plus everything JNI needs to call back into Kotlin. The
// listener is a global ref because the callbacks come from the link thread,
// long after the call that created it has returned.
struct Session {
    std::unique_ptr<MotorLink> link;
    jobject listener = nullptr;
    jmethodID onEvent = nullptr;
    jmethodID onLink = nullptr;

    // The most recent capture, held between requestCapture() and copySamples()
    // so the samples cross the boundary once, into an array Kotlin sized from
    // the header it was just given.
    MotorLink::CaptureResult capture;

    ~Session() {
        // Drop the link (and its thread) first: a callback arriving after the
        // global ref is deleted would call through a dangling reference.
        link.reset();
        if (listener != nullptr && gVm != nullptr) {
            JNIEnv* env = nullptr;
            if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
                env->DeleteGlobalRef(listener);
            }
        }
    }
};

// Attaches the calling thread for as long as this object lives. The link
// thread is native and outlives many Java frames, so it is attached on first
// use and detached when it ends; attaching per callback would be a thread
// registration per second, forever.
class ScopedEnv {
public:
    ScopedEnv() {
        if (gVm == nullptr) return;
        if (gVm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_OK) return;
        JavaVMAttachArgs args{JNI_VERSION_1_6, "MotorGuardLink", nullptr};
        if (gVm->AttachCurrentThreadAsDaemon(&env_, &args) != JNI_OK) env_ = nullptr;
    }
    JNIEnv* get() const { return env_; }

private:
    JNIEnv* env_ = nullptr;
};

Session* asSession(jlong handle) { return reinterpret_cast<Session*>(handle); }

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_motorguard_ivi_data_vehicle_someip_MotorLinkNative_nativeOpen(
        JNIEnv* env, jobject, jobject listener, jint serviceId, jint instanceId,
        jint majorVersion, jint eventgroupId, jint eventId, jint captureMethodId, jint clientId,
        jstring sdMulticast, jint sdPort, jint localEventPort, jstring staticHost,
        jint staticUdpPort, jint staticTcpPort, jint subscribeTtlSec, jint captureTimeoutMs,
        jlong androidNetworkHandle) {
    auto session = std::make_unique<Session>();

    const jclass cls = env->GetObjectClass(listener);
    session->onEvent = env->GetMethodID(cls, "onEvent", "(IIIJFF)V");
    session->onLink = env->GetMethodID(cls, "onLink", "(I)V");
    if (session->onEvent == nullptr || session->onLink == nullptr) {
        LOGE("listener is missing onEvent/onLink");
        return 0;
    }
    session->listener = env->NewGlobalRef(listener);

    MotorLinkConfig cfg;
    cfg.serviceId = static_cast<uint16_t>(serviceId);
    cfg.instanceId = static_cast<uint16_t>(instanceId);
    cfg.majorVersion = static_cast<uint8_t>(majorVersion);
    cfg.eventgroupId = static_cast<uint16_t>(eventgroupId);
    cfg.eventId = static_cast<uint16_t>(eventId);
    cfg.captureMethodId = static_cast<uint16_t>(captureMethodId);
    cfg.clientId = static_cast<uint16_t>(clientId);
    cfg.sdMulticast = toStdString(env, sdMulticast);
    cfg.sdPort = static_cast<uint16_t>(sdPort);
    cfg.localEventPort = static_cast<uint16_t>(localEventPort);
    cfg.staticHost = toStdString(env, staticHost);
    cfg.staticUdpPort = static_cast<uint16_t>(staticUdpPort);
    cfg.staticTcpPort = static_cast<uint16_t>(staticTcpPort);
    cfg.subscribeTtlSec = static_cast<uint32_t>(subscribeTtlSec);
    cfg.captureTimeoutMs = static_cast<uint32_t>(captureTimeoutMs);
    cfg.androidNetworkHandle = static_cast<uint64_t>(androidNetworkHandle);

    Session* raw = session.get();
    raw->link = MotorLink::start(
            cfg,
            [raw](const MotorEvent& e) {
                ScopedEnv scoped;
                JNIEnv* callbackEnv = scoped.get();
                if (callbackEnv == nullptr) return;
                callbackEnv->CallVoidMethod(raw->listener, raw->onEvent,
                                            static_cast<jint>(e.faultType),
                                            static_cast<jint>(e.severity),
                                            static_cast<jint>(e.flags),
                                            static_cast<jlong>(e.timestampMs), e.rulHours,
                                            e.rulPercent);
                if (callbackEnv->ExceptionCheck()) callbackEnv->ExceptionClear();
            },
            [raw](LinkState s) {
                ScopedEnv scoped;
                JNIEnv* callbackEnv = scoped.get();
                if (callbackEnv == nullptr) return;
                callbackEnv->CallVoidMethod(raw->listener, raw->onLink, static_cast<jint>(s));
                if (callbackEnv->ExceptionCheck()) callbackEnv->ExceptionClear();
            });

    if (raw->link == nullptr) {
        LOGE("link failed to start");
        return 0;
    }
    return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT void JNICALL Java_com_motorguard_ivi_data_vehicle_someip_MotorLinkNative_nativeClose(
        JNIEnv*, jobject, jlong handle) {
    delete asSession(handle);
}

JNIEXPORT void JNICALL Java_com_motorguard_ivi_data_vehicle_someip_MotorLinkNative_nativeReconnect(
        JNIEnv*, jobject, jlong handle) {
    if (Session* s = asSession(handle); s != nullptr && s->link != nullptr) s->link->reconnect();
}

// header out-params, all as long so one array carries them:
//   0 status          0 ok, 1..4 peer status, negative CaptureError
//   1 channelCount
//   2 sampleCount     per channel
//   3 sampleRateMilliHz   actual rate x1000, integral for any sane rate
//   4 capturedAtMs    the peer's own time base (docs/10 §3.5)
//   5 headerLayout    20 packed or 24 aligned, for the log only
JNIEXPORT jint JNICALL
Java_com_motorguard_ivi_data_vehicle_someip_MotorLinkNative_nativeRequestCapture(
        JNIEnv* env, jobject, jlong handle, jfloat requestedDurationSec, jlongArray header) {
    Session* s = asSession(handle);
    if (s == nullptr || s->link == nullptr) return motorguard::someip::kErrNoEndpoint;
    if (env->GetArrayLength(header) < 6) return motorguard::someip::kErrMalformed;

    s->capture = s->link->requestCapture(requestedDurationSec);

    const CaptureHeader& h = s->capture.header;
    jlong out[6] = {
            static_cast<jlong>(s->capture.status),
            static_cast<jlong>(h.channelCount),
            static_cast<jlong>(h.sampleCount),
            static_cast<jlong>(h.sampleRateHz * 1000.0f),
            static_cast<jlong>(h.capturedAtMs),
            static_cast<jlong>(h.headerSize),
    };
    env->SetLongArrayRegion(header, 0, 6, out);
    return s->capture.status;
}

JNIEXPORT jboolean JNICALL
Java_com_motorguard_ivi_data_vehicle_someip_MotorLinkNative_nativeCopySamples(
        JNIEnv* env, jobject, jlong handle, jfloatArray dest) {
    Session* s = asSession(handle);
    if (s == nullptr) return JNI_FALSE;
    const size_t have = s->capture.samples.size();
    if (have == 0) return JNI_FALSE;
    if (static_cast<size_t>(env->GetArrayLength(dest)) != have) return JNI_FALSE;

    env->SetFloatArrayRegion(dest, 0, static_cast<jsize>(have), s->capture.samples.data());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_motorguard_ivi_data_vehicle_someip_MotorLinkNative_nativeReleaseCapture(JNIEnv*, jobject,
                                                                                jlong handle) {
    // 9.6 MB held natively on top of the copy Kotlin now owns. Freeing it the
    // moment the array is filled is the difference between one capture in
    // flight and two resident for as long as the panel is open.
    if (Session* s = asSession(handle); s != nullptr) {
        s->capture.samples.clear();
        s->capture.samples.shrink_to_fit();
    }
}

JNIEXPORT void JNICALL
Java_com_motorguard_ivi_data_vehicle_someip_MotorLinkNative_nativeCancelCapture(JNIEnv*, jobject,
                                                                               jlong handle) {
    if (Session* s = asSession(handle); s != nullptr && s->link != nullptr) {
        s->link->cancelCapture();
    }
}

}  // extern "C"
