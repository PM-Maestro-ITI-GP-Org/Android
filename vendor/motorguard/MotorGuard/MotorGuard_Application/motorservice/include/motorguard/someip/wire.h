// ---------------------------------------------------------------------------
// SOME/IP wire format — header framing and the two MotorDiag payloads.
//
// Everything here is pure byte pushing with no sockets and no state, which is
// what makes it testable on the host (see test/wire_test.cpp). The socket and
// discovery layers are in sd.h / motor_link.h and depend on this, never the
// other way round.
//
// SOME/IP is big-endian on the wire, always. Both ends of this link happen to
// be little-endian ARM today, so a host-order shortcut would work on the bench
// and fail the first time anything else joins the bus; the conversions below
// are not optional politeness.
// ---------------------------------------------------------------------------
#pragma once

#include <cstddef>
#include <cstdint>

namespace motorguard::someip {

// --- big-endian primitives -------------------------------------------------
// Written as explicit shifts rather than htons/htonl + memcpy so that reads
// need no alignment: SOME/IP packs a uint64 at offset 16 of a payload whose
// own start is 16 bytes into the datagram, and the capture response puts
// float32s wherever the counts land them.

uint8_t  readU8(const uint8_t* p);
uint16_t readU16(const uint8_t* p);
uint32_t readU24(const uint8_t* p);
uint32_t readU32(const uint8_t* p);
uint64_t readU64(const uint8_t* p);
float    readF32(const uint8_t* p);

void writeU8(uint8_t* p, uint8_t v);
void writeU16(uint8_t* p, uint16_t v);
void writeU24(uint8_t* p, uint32_t v);
void writeU32(uint8_t* p, uint32_t v);

// --- message header --------------------------------------------------------

constexpr size_t kHeaderSize = 16;

// `length` covers requestId onwards, i.e. 8 + payload. A header therefore
// describes a datagram of kHeaderSize + length - 8 bytes.
constexpr size_t kLengthPrefix = 8;

enum MessageType : uint8_t {
    kRequest = 0x00,
    kRequestNoReturn = 0x01,
    kNotification = 0x02,
    kResponse = 0x80,
    kError = 0x81,
};

enum ReturnCode : uint8_t {
    kOk = 0x00,
    kNotOk = 0x01,
    kUnknownService = 0x02,
    kUnknownMethod = 0x03,
    kNotReady = 0x04,
};

struct Header {
    uint16_t service = 0;
    uint16_t method = 0;  // method id, or event id with the top bit set
    uint32_t length = 0;
    uint16_t client = 0;
    uint16_t session = 0;
    uint8_t protocolVersion = 0x01;
    uint8_t interfaceVersion = 0x01;
    uint8_t messageType = kRequest;
    uint8_t returnCode = kOk;

    // Payload size implied by `length`, or 0 if length is too small to be real.
    size_t payloadSize() const;
};

void encodeHeader(const Header& h, uint8_t* out);

// False when `len` is short of a full header or the header's own length field
// contradicts it. Everything read off a socket goes through this before any
// field of it is believed.
bool decodeHeader(const uint8_t* p, size_t len, Header* out);

// --- the 1 Hz fault event (docs/10 §3.2) -----------------------------------
//
// 16 bytes, offsets fixed by that table:
//   0 u8  layoutVersion (1)
//   1 u8  faultType     0 NORMAL, 1 ELECTRICAL, 2 MECHANICAL, 3 SENSOR
//   2 u8  severity      0 none, 1 advisory, 2 urgent
//   3 u8  flags         bit0 rulValid, bit1 rulPercentValid
//   4 u32 timestampMs   monotonic on the sender
//   8 f32 rulHours      ignored unless rulValid
//  12 f32 rulPercent    ignored unless rulPercentValid

constexpr uint8_t kLayoutVersion = 1;
constexpr size_t kEventPayloadSize = 16;

constexpr uint8_t kFlagRulValid = 0x01;
constexpr uint8_t kFlagRulPercentValid = 0x02;

struct MotorEvent {
    uint8_t faultType = 0;
    uint8_t severity = 0;
    uint8_t flags = 0;
    uint32_t timestampMs = 0;
    float rulHours = 0.f;
    float rulPercent = 0.f;
};

// Rejects a wrong layout version or a short payload. Field *values* are not
// judged here — an unknown severity is the Kotlin side's decision to make
// (docs/09 §2.3 maps it to CRITICAL), not a reason to drop the message.
bool decodeMotorEvent(const uint8_t* p, size_t len, MotorEvent* out);

// --- the capture response (docs/10 §5.3) -----------------------------------
//
// The table in docs/10 lists fields without offsets, which leaves the uint64
// either packed at 12 or aligned at 16 depending on whose struct wrote it —
// a difference that produces plausible-looking garbage rather than an error.
// Both are therefore accepted, and the arbiter is arithmetic: the payload
// must be exactly headerSize + channelCount * sampleCount * 4 bytes. A wrong
// guess misses that by megabytes.
//
//   packed (20):  0 u8 ver | 1 u8 status | 2 u16 channels | 4 u32 samples
//                 8 f32 rateHz | 12 u64 capturedAtMs | 20 f32[] samples
//   aligned (24): as above but capturedAtMs at 16, samples at 24
//
// Whichever arrives is logged once per capture so a mismatch between the two
// ends shows up in the bug report rather than in the plot.

constexpr size_t kCapturePackedHeader = 20;
constexpr size_t kCaptureAlignedHeader = 24;
constexpr uint16_t kCaptureChannels = 12;

enum CaptureStatus : uint8_t {
    kCaptureOk = 0,
    kCaptureBusy = 1,
    kCaptureAcquisitionFailed = 2,
    kCaptureNotReady = 3,
    kCaptureUnsupportedDuration = 4,
};

struct CaptureHeader {
    uint8_t status = kCaptureOk;
    uint16_t channelCount = 0;
    uint32_t sampleCount = 0;
    float sampleRateHz = 0.f;
    uint64_t capturedAtMs = 0;
    size_t headerSize = 0;  // which of the two layouts matched
};

// True only when the payload is self-consistent: known layout version, a
// header whose size the byte count agrees with, and — when status is OK — a
// sample block of exactly the length the counts claim. A non-OK status is
// allowed to carry no samples at all, since there are none to send.
bool decodeCaptureHeader(const uint8_t* p, size_t len, CaptureHeader* out);

// Fills `out` with sampleCount * channelCount floats in the order they arrive
// (channel-major, docs/10 §5.3). Returns false if the buffer disagrees with
// the header, or if any sample is NaN or infinite — the plot's min/max
// decimation has no way to represent one, so a poisoned channel is rejected
// at the boundary rather than drawn.
bool decodeCaptureSamples(const uint8_t* p, size_t len, const CaptureHeader& h, float* out);

// Request payload for the capture method (docs/10 §5.2): layout version plus
// an optional requested duration. Always 5 bytes; the sender clamps.
constexpr size_t kCaptureRequestSize = 5;
void encodeCaptureRequest(float requestedDurationSec, uint8_t* out);

}  // namespace motorguard::someip
