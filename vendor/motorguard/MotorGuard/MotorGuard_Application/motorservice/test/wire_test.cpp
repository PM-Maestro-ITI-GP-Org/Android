// Host tests for the two layers that have no sockets in them: the SOME/IP
// framing and the SD entry/option encoding.
//
// These are the parts where being wrong is quiet. A malformed SD subscribe is
// not rejected by the peer, it is ignored, and the only symptom is a link that
// never comes up; a capture header read at the wrong offset yields a plausible
// sample count and a plot of noise. Both are cheap to pin down here and
// expensive to chase on a device.
//
//   atest --host motorguard_someip_test
//   (or: m motorguard_someip_test && out/host/linux-x86/bin/motorguard_someip_test)

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

#include "motorguard/someip/sd.h"
#include "motorguard/someip/wire.h"

using namespace motorguard::someip;

namespace {

std::vector<uint8_t> buildEvent(uint8_t layout, uint8_t faultType, uint8_t severity, uint8_t flags,
                                uint32_t timestamp, float rulHours, float rulPercent) {
    std::vector<uint8_t> p(kEventPayloadSize);
    p[0] = layout;
    p[1] = faultType;
    p[2] = severity;
    p[3] = flags;
    writeU32(p.data() + 4, timestamp);
    uint32_t bits;
    std::memcpy(&bits, &rulHours, 4);
    writeU32(p.data() + 8, bits);
    std::memcpy(&bits, &rulPercent, 4);
    writeU32(p.data() + 12, bits);
    return p;
}

// `headerSize` picks the layout: 24 puts capturedAtMs at offset 16, 20 packs it
// at 12. Both are accepted by the decoder; this is how each is produced.
std::vector<uint8_t> buildCapture(size_t headerSize, uint8_t status, uint16_t channels,
                                  uint32_t samples, float rateHz, uint64_t capturedAt,
                                  const std::vector<float>& values) {
    std::vector<uint8_t> p(headerSize + values.size() * 4, 0);
    p[0] = kLayoutVersion;
    p[1] = status;
    writeU16(p.data() + 2, channels);
    writeU32(p.data() + 4, samples);
    uint32_t bits;
    std::memcpy(&bits, &rateHz, 4);
    writeU32(p.data() + 8, bits);
    const size_t tsOffset = headerSize == kCaptureAlignedHeader ? 16 : 12;
    writeU32(p.data() + tsOffset, static_cast<uint32_t>(capturedAt >> 32));
    writeU32(p.data() + tsOffset + 4, static_cast<uint32_t>(capturedAt));
    for (size_t i = 0; i < values.size(); ++i) {
        float v = values[i];
        std::memcpy(&bits, &v, 4);
        writeU32(p.data() + headerSize + i * 4, bits);
    }
    return p;
}

std::vector<float> ramp(size_t n) {
    std::vector<float> v(n);
    for (size_t i = 0; i < n; ++i) v[i] = static_cast<float>(i);
    return v;
}

}  // namespace

TEST(Header, RoundTrips) {
    Header h;
    h.service = 0x1241;
    h.method = 0x8001;
    h.length = kLengthPrefix + kEventPayloadSize;
    h.client = 0x1341;
    h.session = 0x0007;
    h.interfaceVersion = 1;
    h.messageType = kNotification;

    uint8_t buf[kHeaderSize];
    encodeHeader(h, buf);

    // Big-endian on the wire, not host order — the first two bytes are the
    // service id most significant first.
    EXPECT_EQ(0x12, buf[0]);
    EXPECT_EQ(0x41, buf[1]);

    std::vector<uint8_t> full(kHeaderSize + kEventPayloadSize, 0);
    std::memcpy(full.data(), buf, kHeaderSize);

    Header out;
    ASSERT_TRUE(decodeHeader(full.data(), full.size(), &out));
    EXPECT_EQ(h.service, out.service);
    EXPECT_EQ(h.method, out.method);
    EXPECT_EQ(h.session, out.session);
    EXPECT_EQ(kNotification, out.messageType);
    EXPECT_EQ(kEventPayloadSize, out.payloadSize());
}

TEST(Header, RejectsTruncatedAndOverlongLength) {
    Header h;
    h.service = 0x1241;
    h.method = 0x8001;
    h.length = kLengthPrefix + 64;  // claims 64 bytes of payload
    uint8_t buf[kHeaderSize];
    encodeHeader(h, buf);

    Header out;
    // Only the header arrived: the claim overruns what we have.
    EXPECT_FALSE(decodeHeader(buf, kHeaderSize, &out));
    // Not even a whole header.
    EXPECT_FALSE(decodeHeader(buf, kHeaderSize - 1, &out));

    h.length = 4;  // shorter than the request id it must cover
    encodeHeader(h, buf);
    EXPECT_FALSE(decodeHeader(buf, kHeaderSize, &out));
}

TEST(MotorEventPayload, DecodesFields) {
    const auto p = buildEvent(kLayoutVersion, 2, 1, kFlagRulValid | kFlagRulPercentValid, 123456,
                              41.5f, 62.5f);
    MotorEvent e;
    ASSERT_TRUE(decodeMotorEvent(p.data(), p.size(), &e));
    EXPECT_EQ(2, e.faultType);
    EXPECT_EQ(1, e.severity);
    EXPECT_EQ(123456u, e.timestampMs);
    EXPECT_FLOAT_EQ(41.5f, e.rulHours);
    EXPECT_FLOAT_EQ(62.5f, e.rulPercent);
}

TEST(MotorEventPayload, RejectsWrongLayoutAndShortPayload) {
    auto p = buildEvent(2, 0, 0, 0, 0, 0, 0);
    MotorEvent e;
    EXPECT_FALSE(decodeMotorEvent(p.data(), p.size(), &e));

    p = buildEvent(kLayoutVersion, 0, 0, 0, 0, 0, 0);
    EXPECT_FALSE(decodeMotorEvent(p.data(), p.size() - 1, &e));
}

TEST(MotorEventPayload, ClearsValidBitForNonFiniteRemainingLife) {
    const auto p = buildEvent(kLayoutVersion, 1, 2, kFlagRulValid | kFlagRulPercentValid, 1,
                              std::nanf(""), std::numeric_limits<float>::infinity());
    MotorEvent e;
    ASSERT_TRUE(decodeMotorEvent(p.data(), p.size(), &e));
    // The event is still good — the classification is what matters — but the
    // card must not be handed a "NaN h" to render.
    EXPECT_EQ(2, e.severity);
    EXPECT_EQ(0, e.flags & kFlagRulValid);
    EXPECT_EQ(0, e.flags & kFlagRulPercentValid);
}

TEST(CapturePayload, DecodesAlignedLayout) {
    const auto values = ramp(kCaptureChannels * 4);
    const auto p = buildCapture(kCaptureAlignedHeader, kCaptureOk, kCaptureChannels, 4, 20000.f,
                                0x0000018FEDCBA987ull, values);

    CaptureHeader h;
    ASSERT_TRUE(decodeCaptureHeader(p.data(), p.size(), &h));
    EXPECT_EQ(kCaptureAlignedHeader, h.headerSize);
    EXPECT_EQ(kCaptureChannels, h.channelCount);
    EXPECT_EQ(4u, h.sampleCount);
    EXPECT_FLOAT_EQ(20000.f, h.sampleRateHz);
    EXPECT_EQ(0x0000018FEDCBA987ull, h.capturedAtMs);

    std::vector<float> out(values.size());
    ASSERT_TRUE(decodeCaptureSamples(p.data(), p.size(), h, out.data()));
    EXPECT_EQ(values, out);
}

TEST(CapturePayload, DecodesPackedLayout) {
    const auto values = ramp(kCaptureChannels * 3);
    const auto p = buildCapture(kCapturePackedHeader, kCaptureOk, kCaptureChannels, 3, 20000.f,
                                1700000000000ull, values);

    CaptureHeader h;
    ASSERT_TRUE(decodeCaptureHeader(p.data(), p.size(), &h));
    // Which layout the sender used is decided by arithmetic, not by hope: only
    // one of the two readings makes the byte count add up.
    EXPECT_EQ(kCapturePackedHeader, h.headerSize);
    EXPECT_EQ(3u, h.sampleCount);
    EXPECT_EQ(1700000000000ull, h.capturedAtMs);
}

TEST(CapturePayload, RejectsRaggedSampleBlock) {
    auto p = buildCapture(kCaptureAlignedHeader, kCaptureOk, kCaptureChannels, 4, 20000.f, 1,
                          ramp(kCaptureChannels * 4));
    // One channel short of what the counts promise. docs/10 §5.4: this is an
    // index-out-of-bounds in the plot if it is let through.
    p.resize(p.size() - 4 * sizeof(float));

    CaptureHeader h;
    EXPECT_FALSE(decodeCaptureHeader(p.data(), p.size(), &h));
}

TEST(CapturePayload, RejectsNonFiniteSample) {
    auto values = ramp(kCaptureChannels * 2);
    values[7] = std::nanf("");
    const auto p = buildCapture(kCaptureAlignedHeader, kCaptureOk, kCaptureChannels, 2, 20000.f, 1,
                                values);

    CaptureHeader h;
    ASSERT_TRUE(decodeCaptureHeader(p.data(), p.size(), &h));
    std::vector<float> out(values.size());
    // A single NaN flattens every window the decimator draws through it.
    EXPECT_FALSE(decodeCaptureSamples(p.data(), p.size(), h, out.data()));
}

TEST(CapturePayload, AcceptsFailureStatusWithoutSamples) {
    const auto p = buildCapture(kCaptureAlignedHeader, kCaptureBusy, 0, 0, 0.f, 0, {});
    CaptureHeader h;
    ASSERT_TRUE(decodeCaptureHeader(p.data(), p.size(), &h));
    EXPECT_EQ(kCaptureBusy, h.status);
}

TEST(CapturePayload, RejectsWrongChannelCount) {
    const auto p = buildCapture(kCaptureAlignedHeader, kCaptureOk, 8, 4, 20000.f, 1, ramp(8 * 4));
    CaptureHeader h;
    EXPECT_FALSE(decodeCaptureHeader(p.data(), p.size(), &h));
}

TEST(Sd, FindIsAWellFormedEntryWithNoOptions) {
    const auto msg = sd::buildFind(0x1241, 0x0001, 1, 5);

    Header h;
    ASSERT_TRUE(decodeHeader(msg.data(), msg.size(), &h));
    EXPECT_EQ(sd::kSdService, h.service);
    EXPECT_EQ(sd::kSdMethod, h.method);
    EXPECT_EQ(kNotification, h.messageType);

    const uint8_t* payload = msg.data() + kHeaderSize;
    EXPECT_EQ(sd::kEntrySize, readU32(payload + 4));
    const uint8_t* entry = payload + 8;
    EXPECT_EQ(sd::kFindService, entry[0]);
    EXPECT_EQ(0x1241, readU16(entry + 4));
    EXPECT_EQ(0x0001, readU16(entry + 6));
    EXPECT_EQ(1, entry[8]);
    EXPECT_EQ(0u, readU32(entry + sd::kEntrySize));  // options length
}

TEST(Sd, SubscribeCarriesOneIpv4EndpointOption) {
    const uint32_t local = 0x0A0002C8;  // 10.0.2.200, already network order
    const auto msg = sd::buildSubscribe(0x1241, 0x0001, 1, 0x0001, 16, local, 40000, 9);

    const uint8_t* payload = msg.data() + kHeaderSize;
    const uint8_t* entry = payload + 8;
    EXPECT_EQ(sd::kSubscribeEventgroup, entry[0]);
    // One option in the first run, none in the second. Getting this nibble
    // wrong makes the peer subscribe us to nowhere, silently.
    EXPECT_EQ(0x10, entry[3]);
    EXPECT_EQ(16u, readU24(entry + 9));
    EXPECT_EQ(0x0001, readU16(entry + 14));

    const uint8_t* options = entry + sd::kEntrySize + 4;
    EXPECT_EQ(sd::kIpv4OptionSize, readU32(entry + sd::kEntrySize));
    EXPECT_EQ(0x0009, readU16(options));  // length counts everything after the type byte
    EXPECT_EQ(0x04, options[2]);          // IPv4 endpoint
    EXPECT_EQ(sd::kUdp, options[9]);
    EXPECT_EQ(40000, readU16(options + 10));
}

namespace {

// A synthetic OfferService with a UDP and a TCP endpoint option, as vsomeip
// would send for a service offering both.
std::vector<uint8_t> buildOffer(uint16_t service, uint16_t instance, uint8_t major, uint32_t ttl,
                                uint32_t addrBe, uint16_t udpPort, uint16_t tcpPort) {
    std::vector<uint8_t> payload;
    auto push16 = [&](uint16_t v) {
        payload.push_back(static_cast<uint8_t>(v >> 8));
        payload.push_back(static_cast<uint8_t>(v));
    };
    auto push32 = [&](uint32_t v) {
        payload.push_back(static_cast<uint8_t>(v >> 24));
        payload.push_back(static_cast<uint8_t>(v >> 16));
        payload.push_back(static_cast<uint8_t>(v >> 8));
        payload.push_back(static_cast<uint8_t>(v));
    };

    payload.push_back(0x40);  // unicast flag
    payload.push_back(0);
    payload.push_back(0);
    payload.push_back(0);
    push32(sd::kEntrySize);

    payload.push_back(sd::kOfferService);
    payload.push_back(0);     // first option run starts at 0
    payload.push_back(0);
    payload.push_back(0x20);  // two options in the first run
    push16(service);
    push16(instance);
    payload.push_back(major);
    payload.push_back(static_cast<uint8_t>(ttl >> 16));
    payload.push_back(static_cast<uint8_t>(ttl >> 8));
    payload.push_back(static_cast<uint8_t>(ttl));
    push32(0);  // minor version

    push32(2 * sd::kIpv4OptionSize);
    for (int i = 0; i < 2; ++i) {
        push16(0x0009);
        payload.push_back(0x04);
        payload.push_back(0);
        // Address bytes go out exactly as they sit in network order.
        for (int b = 0; b < 4; ++b) {
            payload.push_back(reinterpret_cast<const uint8_t*>(&addrBe)[b]);
        }
        payload.push_back(0);
        payload.push_back(i == 0 ? sd::kUdp : sd::kTcp);
        push16(i == 0 ? udpPort : tcpPort);
    }

    Header h;
    h.service = sd::kSdService;
    h.method = sd::kSdMethod;
    h.length = static_cast<uint32_t>(kLengthPrefix + payload.size());
    h.interfaceVersion = sd::kSdInterfaceVersion;
    h.messageType = kNotification;

    std::vector<uint8_t> msg(kHeaderSize + payload.size());
    encodeHeader(h, msg.data());
    std::memcpy(msg.data() + kHeaderSize, payload.data(), payload.size());
    return msg;
}

}  // namespace

TEST(Sd, ParsesOfferWithBothEndpoints) {
    uint32_t addr = 0;
    const uint8_t octets[4] = {10, 0, 2, 2};  // 10.0.2.2
    std::memcpy(&addr, octets, 4);

    const auto msg = buildOffer(0x1241, 0x0001, 1, 3, addr, 30502, 30503);

    sd::Message parsed;
    ASSERT_TRUE(sd::parse(msg.data(), msg.size(), &parsed));
    ASSERT_EQ(1u, parsed.offers.size());

    const auto& offer = parsed.offers[0];
    EXPECT_EQ(0x1241, offer.service);
    EXPECT_EQ(0x0001, offer.instance);
    EXPECT_FALSE(offer.isStop());
    EXPECT_EQ(addr, offer.udp.addressBe);
    EXPECT_EQ(30502, offer.udp.port);
    EXPECT_EQ(30503, offer.tcp.port);
    EXPECT_TRUE(offer.udp.valid());
    EXPECT_TRUE(offer.tcp.valid());
}

TEST(Sd, TtlZeroOfferIsAStop) {
    uint32_t addr = 0;
    const uint8_t octets[4] = {10, 0, 2, 2};
    std::memcpy(&addr, octets, 4);

    const auto msg = buildOffer(0x1241, 0x0001, 1, 0, addr, 30502, 30503);
    sd::Message parsed;
    ASSERT_TRUE(sd::parse(msg.data(), msg.size(), &parsed));
    ASSERT_EQ(1u, parsed.offers.size());
    // The difference between "here I am" and "I am going away" is one field,
    // and treating a stop as an offer means talking to a service that has gone.
    EXPECT_TRUE(parsed.offers[0].isStop());
}

TEST(Sd, RejectsNonSdAndTruncatedMessages) {
    Header h;
    h.service = 0x1241;  // an ordinary message, not SD
    h.method = 0x8001;
    h.length = kLengthPrefix;
    std::vector<uint8_t> msg(kHeaderSize);
    encodeHeader(h, msg.data());

    sd::Message parsed;
    EXPECT_FALSE(sd::parse(msg.data(), msg.size(), &parsed));

    uint32_t addr = 0;
    const uint8_t octets[4] = {10, 0, 2, 2};
    std::memcpy(&addr, octets, 4);
    auto offer = buildOffer(0x1241, 0x0001, 1, 3, addr, 30502, 30503);
    offer.resize(offer.size() - 6);  // options array now overruns the payload
    EXPECT_FALSE(sd::parse(offer.data(), offer.size(), &parsed));
}
