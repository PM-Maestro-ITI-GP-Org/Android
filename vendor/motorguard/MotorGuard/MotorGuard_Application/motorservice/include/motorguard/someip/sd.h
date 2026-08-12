// ---------------------------------------------------------------------------
// SOME/IP-SD — only the client half, and only the parts this link uses.
//
// We find one service, subscribe to one eventgroup, and read back where its
// two endpoints live. We never offer anything, never answer a FindService and
// never publish an eventgroup, so all the entry types concerned with being a
// server are absent by design rather than by omission.
//
// Like wire.h this is pure encode/decode over caller-owned buffers: the
// sockets live in motor_link.cpp. That is what lets the entry and option
// layouts be tested on the host, which matters more here than anywhere else
// in this library — an SD message that is wrong by one byte is not rejected
// by the peer, it is silently ignored, and the symptom is a link that simply
// never comes up.
// ---------------------------------------------------------------------------
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "motorguard/someip/wire.h"

namespace motorguard::someip::sd {

// SD is itself a SOME/IP message, addressed to a reserved service/method.
constexpr uint16_t kSdService = 0xFFFF;
constexpr uint16_t kSdMethod = 0x8100;
constexpr uint8_t kSdInterfaceVersion = 0x01;

constexpr size_t kEntrySize = 16;
constexpr size_t kIpv4OptionSize = 12;

enum EntryType : uint8_t {
    kFindService = 0x00,
    kOfferService = 0x01,
    kSubscribeEventgroup = 0x06,
    kSubscribeEventgroupAck = 0x07,
};

enum L4Protocol : uint8_t {
    kUdp = 0x11,
    kTcp = 0x06,
};

// A service instance's transport endpoint, as carried by an IPv4 endpoint
// option. Addresses stay in network byte order — they are only ever handed
// back to the socket layer, and converting twice is how they end up reversed.
struct Endpoint {
    uint32_t addressBe = 0;
    uint16_t port = 0;
    uint8_t protocol = kUdp;

    bool valid() const { return addressBe != 0 && port != 0; }
};

// What a received OfferService told us. TTL 0 is a *stop* offer — the service
// is going away — and is reported as such rather than filtered out, because
// tearing the link down promptly is what turns the screen to "No data" inside
// the 15 s budget instead of waiting for the event timeout.
struct Offer {
    uint16_t service = 0;
    uint16_t instance = 0;
    uint8_t majorVersion = 0;
    uint32_t minorVersion = 0;
    uint32_t ttl = 0;
    Endpoint udp;
    Endpoint tcp;

    bool isStop() const { return ttl == 0; }
};

struct SubscribeAck {
    uint16_t service = 0;
    uint16_t instance = 0;
    uint16_t eventgroup = 0;
    uint32_t ttl = 0;

    bool isNack() const { return ttl == 0; }
};

// Everything a single SD datagram told us that we care about. One datagram can
// carry several entries — vsomeip routinely packs an offer for every instance
// it hosts into one message — so both lists can be non-empty at once.
struct Message {
    std::vector<Offer> offers;
    std::vector<SubscribeAck> acks;
};

// Builds `FindService` for one service/instance. Pass instance 0xFFFF to find
// any instance. Returns the encoded datagram.
std::vector<uint8_t> buildFind(uint16_t service, uint16_t instance, uint8_t majorVersion,
                               uint16_t sessionId);

// Builds `SubscribeEventgroup`, carrying one IPv4 endpoint option describing
// where *we* want the events delivered. `ttl` is in seconds; a subscription
// must be renewed before it expires (motor_link renews at half).
//
// Sending this with ttl 0 is the unsubscribe, which is worth doing on a clean
// shutdown: without it the peer keeps sending 1 Hz events into a closed socket
// until the TTL runs out, and on a device that reconnects often the peer's
// subscriber table fills with ghosts.
std::vector<uint8_t> buildSubscribe(uint16_t service, uint16_t instance, uint8_t majorVersion,
                                    uint16_t eventgroup, uint32_t ttl, uint32_t localAddressBe,
                                    uint16_t localPort, uint16_t sessionId);

// Parses a received SD datagram. Returns false if it is not SD at all or is
// malformed; a message whose entries are all types we ignore parses fine and
// yields an empty result.
bool parse(const uint8_t* p, size_t len, Message* out);

}  // namespace motorguard::someip::sd
