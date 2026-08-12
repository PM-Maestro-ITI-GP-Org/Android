#include "motorguard/someip/wire.h"

#include <cmath>
#include <cstring>

namespace motorguard::someip {

uint8_t readU8(const uint8_t* p) { return p[0]; }

uint16_t readU16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] << 8 | p[1]);
}

uint32_t readU24(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) << 16 | static_cast<uint32_t>(p[1]) << 8 |
           static_cast<uint32_t>(p[2]);
}

uint32_t readU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) << 24 | static_cast<uint32_t>(p[1]) << 16 |
           static_cast<uint32_t>(p[2]) << 8 | static_cast<uint32_t>(p[3]);
}

uint64_t readU64(const uint8_t* p) {
    return static_cast<uint64_t>(readU32(p)) << 32 | readU32(p + 4);
}

float readF32(const uint8_t* p) {
    // Bit-cast through memcpy: the obvious reinterpret_cast is undefined
    // behaviour and, more practically, would need the source to be aligned.
    const uint32_t bits = readU32(p);
    float f;
    std::memcpy(&f, &bits, sizeof f);
    return f;
}

void writeU8(uint8_t* p, uint8_t v) { p[0] = v; }

void writeU16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v >> 8);
    p[1] = static_cast<uint8_t>(v);
}

void writeU24(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v >> 16);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v);
}

void writeU32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v >> 24);
    p[1] = static_cast<uint8_t>(v >> 16);
    p[2] = static_cast<uint8_t>(v >> 8);
    p[3] = static_cast<uint8_t>(v);
}

size_t Header::payloadSize() const {
    return length < kLengthPrefix ? 0 : length - kLengthPrefix;
}

void encodeHeader(const Header& h, uint8_t* out) {
    writeU16(out + 0, h.service);
    writeU16(out + 2, h.method);
    writeU32(out + 4, h.length);
    writeU16(out + 8, h.client);
    writeU16(out + 10, h.session);
    out[12] = h.protocolVersion;
    out[13] = h.interfaceVersion;
    out[14] = h.messageType;
    out[15] = h.returnCode;
}

bool decodeHeader(const uint8_t* p, size_t len, Header* out) {
    if (len < kHeaderSize) return false;

    Header h;
    h.service = readU16(p + 0);
    h.method = readU16(p + 2);
    h.length = readU32(p + 4);
    h.client = readU16(p + 8);
    h.session = readU16(p + 10);
    h.protocolVersion = p[12];
    h.interfaceVersion = p[13];
    h.messageType = p[14];
    h.returnCode = p[15];

    // A length that does not cover the request id cannot be a SOME/IP message,
    // and a length that overruns what was received is either truncation or a
    // hostile size — both are refusals, not clamps.
    if (h.length < kLengthPrefix) return false;
    if (kHeaderSize + h.payloadSize() > len) return false;

    *out = h;
    return true;
}

bool decodeMotorEvent(const uint8_t* p, size_t len, MotorEvent* out) {
    if (len < kEventPayloadSize) return false;
    if (readU8(p) != kLayoutVersion) return false;

    MotorEvent e;
    e.faultType = p[1];
    e.severity = p[2];
    e.flags = p[3];
    e.timestampMs = readU32(p + 4);
    e.rulHours = readF32(p + 8);
    e.rulPercent = readF32(p + 12);

    // A remaining-life figure that is not a number is worse than no figure:
    // the card would render "NaN h". The valid bits are what say "no estimate",
    // so an unusable value clears them rather than propagating.
    if (!std::isfinite(e.rulHours)) e.flags &= static_cast<uint8_t>(~kFlagRulValid);
    if (!std::isfinite(e.rulPercent)) e.flags &= static_cast<uint8_t>(~kFlagRulPercentValid);

    *out = e;
    return true;
}

namespace {

// One candidate reading of the capture header. `ok` only means the fields were
// in range — whether this is the *right* reading is decided by byte count.
bool readCaptureAt(const uint8_t* p, size_t len, size_t headerSize, CaptureHeader* out) {
    if (len < headerSize) return false;

    CaptureHeader h;
    h.status = p[1];
    h.channelCount = readU16(p + 2);
    h.sampleCount = readU32(p + 4);
    h.sampleRateHz = readF32(p + 8);
    h.capturedAtMs = readU64(p + (headerSize == kCaptureAlignedHeader ? 16 : 12));
    h.headerSize = headerSize;

    if (h.status != kCaptureOk) {
        // A failure carries no samples, so there is no arithmetic to check it
        // against. Accept it on the channel count alone and let the caller
        // render the status; a wrong layout guess here costs a wrong error
        // message, not a wrong plot.
        if (h.channelCount != 0 && h.channelCount != kCaptureChannels) return false;
        *out = h;
        return true;
    }

    if (h.channelCount != kCaptureChannels) return false;
    if (h.sampleCount == 0) return false;
    if (!std::isfinite(h.sampleRateHz) || h.sampleRateHz <= 0.f) return false;

    const uint64_t samples = static_cast<uint64_t>(h.channelCount) * h.sampleCount;
    if (samples > (1u << 26)) return false;  // 67 M floats; nothing legitimate is this big
    if (headerSize + samples * sizeof(float) != len) return false;

    *out = h;
    return true;
}

}  // namespace

bool decodeCaptureHeader(const uint8_t* p, size_t len, CaptureHeader* out) {
    if (len < kCapturePackedHeader) return false;
    if (readU8(p) != kLayoutVersion) return false;

    // Aligned first: on a well-formed aligned payload the packed reading finds
    // the top half of capturedAtMs where the sample block should start and
    // fails the byte count, but trying the stricter interpretation first means
    // a well-formed payload is never diagnosed by the loser's error.
    if (readCaptureAt(p, len, kCaptureAlignedHeader, out)) return true;
    return readCaptureAt(p, len, kCapturePackedHeader, out);
}

bool decodeCaptureSamples(const uint8_t* p, size_t len, const CaptureHeader& h, float* out) {
    const uint64_t count = static_cast<uint64_t>(h.channelCount) * h.sampleCount;
    if (h.headerSize + count * sizeof(float) != len) return false;

    const uint8_t* src = p + h.headerSize;
    for (uint64_t i = 0; i < count; ++i) {
        const float v = readF32(src + i * sizeof(float));
        // docs/10 §5.4: no NaN, no infinity. One of either silently destroys
        // the min/max decimation the plot is built on — every window touching
        // it collapses to a flat line at an arbitrary place — so the whole
        // capture is refused here and reported as a failure the user can read.
        if (!std::isfinite(v)) return false;
        out[i] = v;
    }
    return true;
}

void encodeCaptureRequest(float requestedDurationSec, uint8_t* out) {
    out[0] = kLayoutVersion;
    uint32_t bits;
    std::memcpy(&bits, &requestedDurationSec, sizeof bits);
    writeU32(out + 1, bits);
}

}  // namespace motorguard::someip
