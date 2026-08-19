#include "motorguard/someip/sd.h"

#include <cstring>

namespace motorguard::someip::sd {
namespace {

// SD payload preamble: flags, three reserved bytes, then the entries array
// length. Options follow the entries with their own length prefix.
constexpr size_t kPreamble = 8;  // flags(1) + reserved(3) + entriesLength(4)

// Unicast flag: "I can receive unicast SD messages", which every implementation
// sets and vsomeip requires before it will answer a find with a unicast offer.
constexpr uint8_t kFlagUnicast = 0x40;

void appendU8(std::vector<uint8_t>& v, uint8_t x) { v.push_back(x); }

void appendU16(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back(static_cast<uint8_t>(x >> 8));
    v.push_back(static_cast<uint8_t>(x));
}

void appendU24(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back(static_cast<uint8_t>(x >> 16));
    v.push_back(static_cast<uint8_t>(x >> 8));
    v.push_back(static_cast<uint8_t>(x));
}

void appendU32(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back(static_cast<uint8_t>(x >> 24));
    v.push_back(static_cast<uint8_t>(x >> 16));
    v.push_back(static_cast<uint8_t>(x >> 8));
    v.push_back(static_cast<uint8_t>(x));
}

// For a value that is already in network byte order in memory (a
// sockaddr_in::sin_addr, not a host integer) -- copied verbatim, not
// re-encoded. appendU32() shifts its input assuming host order, which is
// right for ttl/service/instance but wrong for an address: feeding it
// localAddressBe there double-converts on a little-endian host and reverses
// the octets on the wire (192.168.2.60 becomes 60.2.168.192, an address
// nothing answers to). collectEndpoints() on the read side already does this
// correctly -- "the four address bytes are already in network order on the
// wire; copying them verbatim is ... correct" -- this is that same rule
// applied to the write side, which buildSubscribe() was missing.
void appendBe32(std::vector<uint8_t>& v, uint32_t addressBe) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(&addressBe);
    v.insert(v.end(), bytes, bytes + sizeof addressBe);
}

// Wraps an SD payload in its SOME/IP header. Session ids must never be 0 —
// a peer treats 0 as "no session tracking" and some stacks reject it — so the
// caller's counter is nudged off zero here rather than at every call site.
std::vector<uint8_t> frame(const std::vector<uint8_t>& payload, uint16_t sessionId) {
    Header h;
    h.service = kSdService;
    h.method = kSdMethod;
    h.length = static_cast<uint32_t>(kLengthPrefix + payload.size());
    h.client = 0x0000;
    h.session = sessionId == 0 ? 1 : sessionId;
    h.protocolVersion = 0x01;
    h.interfaceVersion = kSdInterfaceVersion;
    h.messageType = kNotification;
    h.returnCode = kOk;

    std::vector<uint8_t> out(kHeaderSize + payload.size());
    encodeHeader(h, out.data());
    std::memcpy(out.data() + kHeaderSize, payload.data(), payload.size());
    return out;
}

}  // namespace

std::vector<uint8_t> buildFind(uint16_t service, uint16_t instance, uint8_t majorVersion,
                               uint16_t sessionId) {
    std::vector<uint8_t> p;
    p.reserve(kPreamble + kEntrySize + 4);

    appendU8(p, kFlagUnicast);
    appendU8(p, 0);
    appendU8(p, 0);
    appendU8(p, 0);
    appendU32(p, kEntrySize);

    appendU8(p, kFindService);
    appendU8(p, 0);  // index of first option run — none
    appendU8(p, 0);  // index of second option run — none
    appendU8(p, 0);  // 4 bits count of each run, both zero
    appendU16(p, service);
    appendU16(p, instance);
    appendU8(p, majorVersion);
    appendU24(p, 3);           // TTL 3 s: a find is a question, not a lease
    appendU32(p, 0xFFFFFFFF);  // any minor version

    appendU32(p, 0);  // options array length

    return frame(p, sessionId);
}

std::vector<uint8_t> buildSubscribe(uint16_t service, uint16_t instance, uint8_t majorVersion,
                                    uint16_t eventgroup, uint32_t ttl, uint32_t localAddressBe,
                                    uint16_t localPort, uint16_t sessionId) {
    std::vector<uint8_t> p;
    p.reserve(kPreamble + kEntrySize + 4 + kIpv4OptionSize);

    appendU8(p, kFlagUnicast);
    appendU8(p, 0);
    appendU8(p, 0);
    appendU8(p, 0);
    appendU32(p, kEntrySize);

    appendU8(p, kSubscribeEventgroup);
    appendU8(p, 0);     // first option run starts at option 0
    appendU8(p, 0);
    appendU8(p, 0x10);  // one option in the first run, none in the second
    appendU16(p, service);
    appendU16(p, instance);
    appendU8(p, majorVersion);
    appendU24(p, ttl);
    appendU8(p, 0);  // reserved
    appendU8(p, 0);  // reserved(4) + counter(4): one eventgroup, so counter 0
    appendU16(p, eventgroup);

    appendU32(p, kIpv4OptionSize);
    appendU16(p, 0x0009);  // length of everything after the type byte
    appendU8(p, 0x04);     // IPv4 endpoint option
    appendU8(p, 0);        // reserved
    appendBe32(p, localAddressBe);
    appendU8(p, 0);  // reserved
    appendU8(p, kUdp);
    appendU16(p, localPort);

    return frame(p, sessionId);
}

namespace {

// Reads the IPv4 endpoint options belonging to one entry's first option run.
// Entries address options by index into the options array, and an option run
// that walks off the end is a malformed message rather than a reason to guess.
bool collectEndpoints(const uint8_t* options, size_t optionsLen, uint8_t firstIndex, uint8_t count,
                      Endpoint* udp, Endpoint* tcp) {
    size_t offset = 0;
    uint8_t index = 0;

    while (offset + 3 <= optionsLen) {
        const uint16_t optLen = readU16(options + offset);
        const uint8_t type = options[offset + 2];
        // The length field counts the bytes *after* the type byte, so an
        // option occupies 2 (length) + 1 (type) + optLen. For the IPv4
        // endpoint option that is 3 + 9 = 12, which is where kIpv4OptionSize
        // comes from; getting this off by one walks the option array into
        // nonsense without ever failing a bounds check.
        const size_t total = 3 + optLen;
        if (optLen == 0 || offset + total > optionsLen) return false;

        if (index >= firstIndex && index < firstIndex + count && type == 0x04 &&
            total == kIpv4OptionSize) {
            Endpoint e;
            // The four address bytes are already in network order on the wire;
            // copying them verbatim is both correct on a big-endian host and
            // one fewer conversion to get backwards.
            std::memcpy(&e.addressBe, options + offset + 4, sizeof e.addressBe);
            e.protocol = options[offset + 9];
            e.port = readU16(options + offset + 10);
            if (e.protocol == kTcp) {
                *tcp = e;
            } else {
                *udp = e;
            }
        }

        offset += total;
        ++index;
    }
    return true;
}

}  // namespace

bool parse(const uint8_t* p, size_t len, Message* out) {
    Header h;
    if (!decodeHeader(p, len, &h)) return false;
    if (h.service != kSdService || h.method != kSdMethod) return false;
    if (h.messageType != kNotification) return false;

    const uint8_t* payload = p + kHeaderSize;
    const size_t payloadLen = h.payloadSize();
    if (payloadLen < kPreamble) return false;

    const uint32_t entriesLen = readU32(payload + 4);
    if (entriesLen % kEntrySize != 0) return false;
    if (kPreamble + entriesLen + 4 > payloadLen) return false;

    const uint8_t* entries = payload + kPreamble;
    const uint32_t optionsLen = readU32(entries + entriesLen);
    const uint8_t* options = entries + entriesLen + 4;
    if (kPreamble + entriesLen + 4 + optionsLen > payloadLen) return false;

    Message msg;
    for (uint32_t e = 0; e < entriesLen; e += kEntrySize) {
        const uint8_t* entry = entries + e;
        const uint8_t type = entry[0];
        const uint8_t firstIndex = entry[1];
        const uint8_t counts = entry[3];
        const uint8_t firstCount = static_cast<uint8_t>(counts >> 4);

        const uint16_t service = readU16(entry + 4);
        const uint16_t instance = readU16(entry + 6);
        const uint8_t major = entry[8];
        const uint32_t ttl = readU24(entry + 9);

        if (type == kOfferService) {
            Offer o;
            o.service = service;
            o.instance = instance;
            o.majorVersion = major;
            o.minorVersion = readU32(entry + 12);
            o.ttl = ttl;
            if (!collectEndpoints(options, optionsLen, firstIndex, firstCount, &o.udp, &o.tcp)) {
                return false;
            }
            msg.offers.push_back(o);
        } else if (type == kSubscribeEventgroupAck) {
            SubscribeAck a;
            a.service = service;
            a.instance = instance;
            a.eventgroup = readU16(entry + 14);
            a.ttl = ttl;
            msg.acks.push_back(a);
        }
        // Everything else — finds from other clients, our own subscribes seen
        // back on the multicast group, offers for services we never asked
        // about — is not an error, just not ours.
    }

    *out = msg;
    return true;
}

}  // namespace motorguard::someip::sd
