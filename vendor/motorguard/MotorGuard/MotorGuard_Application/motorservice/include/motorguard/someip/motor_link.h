// ---------------------------------------------------------------------------
// The motor diagnostics link: one SOME/IP client, one background thread.
//
// Two things happen over it, and they are deliberately not symmetrical:
//
//   * the 1 Hz fault event arrives unasked over UDP, and is pushed straight
//     into Kotlin as it lands. Nothing is buffered or coalesced — docs/09 §2.2
//     says emit every message, because the freshness machine upstairs is
//     counting them.
//
//   * a capture is asked for, once at a time, over its own short-lived TCP
//     connection on the caller's thread. Nine and a half megabytes has no
//     business on the thread that has to stay responsive to a 1 Hz heartbeat,
//     and a request that fails or is abandoned must not be able to wedge the
//     event path. Keeping them on separate sockets and separate threads is
//     what makes that structural rather than careful.
//
// Lifetime: start() spawns the thread, the destructor stops it and joins. The
// object is not restartable — reconnect() re-runs discovery on the existing
// one, which is what VehicleDataSource.reconnect() is for.
// ---------------------------------------------------------------------------
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "motorguard/someip/sd.h"
#include "motorguard/someip/wire.h"

namespace motorguard::someip {

struct MotorLinkConfig {
    // Identity, per docs/10 §2. Defaults are the assignment made in
    // motorservice/README.md; the Kotlin side passes them explicitly so the
    // numbers live in one place that is greppable from both languages.
    uint16_t serviceId = 0x1241;
    uint16_t instanceId = 0x0001;
    uint8_t majorVersion = 1;
    uint16_t eventgroupId = 0x0001;
    uint16_t eventId = 0x8001;
    uint16_t captureMethodId = 0x0002;

    // Ours on the bus. Only has to be unique among clients of this service.
    uint16_t clientId = 0x1341;

    // Service discovery. Empty multicast address disables SD entirely, which
    // only makes sense together with a static host below.
    std::string sdMulticast = "224.244.224.245";
    uint16_t sdPort = 30490;

    // Where events are delivered. 0 asks the kernel for a port, which is the
    // right default: the port is advertised in the subscribe, so nothing needs
    // to agree on it in advance, and a fixed port collides after a crash.
    uint16_t localEventPort = 0;

    // Static endpoints, bypassing discovery. This exists because multicast is
    // the first thing to be missing on a bench network or through a hypervisor
    // bridge, and "the link works with -Dhost=... but not with SD" is a far
    // more useful bug report than "the link does not work".
    std::string staticHost;
    uint16_t staticUdpPort = 30502;
    uint16_t staticTcpPort = 30503;

    uint32_t subscribeTtlSec = 16;  // renewed at half
    uint32_t captureTimeoutMs = 20000;  // docs/09 §5.3 hard ceiling
};

// What the link knows about the peer. The Kotlin side only distinguishes
// "events can be expected" from "they cannot"; the intermediate state exists
// so a link that finds the service but is never acknowledged is visibly
// different in the log from one that never finds it at all.
enum class LinkState : int {
    kDown = 0,
    kOffered = 1,
    kSubscribed = 2,
};

// Negative status values returned by requestCapture, distinct from the
// protocol's own 0..4 (docs/10 §5.3). These are what the failure sentences in
// Kotlin are chosen from, so each one names a different thing to go and fix.
enum CaptureError : int {
    kErrNoEndpoint = -1,   // never discovered; nothing to connect to
    kErrConnect = -2,      // refused, unreachable, or connect timed out
    kErrIo = -3,           // connection died mid-transfer
    kErrTimeout = -4,      // peer went quiet, ceiling reached
    kErrMalformed = -5,    // decoded to something that is not a capture
    kErrCancelled = -6,    // the panel was closed; nobody is waiting
    kErrBadSamples = -7,   // ragged or non-finite; refused at the boundary
};

class MotorLink {
public:
    using EventFn = std::function<void(const MotorEvent&)>;
    using StateFn = std::function<void(LinkState)>;

    // Returns null if the sockets could not be opened at all — a device with
    // no network, essentially. Everything softer than that (no peer, no
    // multicast route, peer not yet up) is a running link in kDown.
    static std::unique_ptr<MotorLink> start(const MotorLinkConfig& config, EventFn onEvent,
                                            StateFn onState);

    ~MotorLink();

    MotorLink(const MotorLink&) = delete;
    MotorLink& operator=(const MotorLink&) = delete;

    // Forgets the peer and re-runs discovery. Safe to call at any time from
    // any thread; a capture in flight is left alone.
    void reconnect();

    struct CaptureResult {
        int status = 0;  // 0 ok, 1..4 peer status, negative CaptureError
        CaptureHeader header;
        std::vector<float> samples;  // channel-major, empty unless status == 0
    };

    // Blocks the calling thread. Only one at a time — the ViewModel enforces
    // that upstairs, and a second concurrent call here returns kErrIo rather
    // than interleaving two responses on one socket.
    CaptureResult requestCapture(float requestedDurationSec);

    // Abandons an in-flight capture from another thread. The blocked call
    // returns kErrCancelled promptly rather than after the 20 s ceiling.
    void cancelCapture();

private:
    MotorLink() = default;

    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace motorguard::someip
