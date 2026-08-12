# 09 · Requirements — diagnostics unit SOME/IP service (Raspberry Pi 5, Yocto)

**Audience:** the engineer implementing the service on the diagnostics Raspberry Pi that publishes
motor data to the AAOS head unit.

**Companion document:** `09-motor-service-aaos.md` covers the Android side. Field meanings, units
and semantics are defined there and are not repeated in full here; this document defines the
**wire contract** and the Pi-side behaviour.

---

## 1. Scope

You own one service offering:

1. A **1 Hz fault event** — the classification, and nothing measured.
2. A **capture request/response method** — the raw samples, only on demand.

That split is the central design decision and is not negotiable without re-planning both sides. The
raw signals are 12 channels at 20 kHz — roughly **1 MB/s** — which is why they are never streamed.

Note what the periodic event does **not** carry: no speed, no power, no bus voltage. Those were
specified and then removed. A 1 Hz sample of a motor whose electrical frequency is ~160 Hz says
almost nothing, and the head unit now derives every measured quantity from the capture instead,
over a window whose length it can state. Publish the classification; let the capture answer
everything else.

### 1.1 The motor

A **48 V, 450 W BLDC**, maximum ~**750 rpm**, **11 or 13 pole pairs** (see §10). At rated power
that is roughly 9.4 A of bus current, and at full speed roughly 160 Hz electrical with 13 pole
pairs. Every range in this document and every plot scale in the app is sized from those numbers,
so confirm them before implementation rather than after.

---

## 2. Service identity and transport

To be fixed before implementation and recorded here:

| Item | Value |
|---|---|
| Service ID | `0x____` (assign) |
| Instance ID | `0x0001` |
| Major / minor version | `1` / `0` |
| Summary event ID | `0x8001` |
| Summary eventgroup | `0x0001` |
| Capture method ID | `0x0002` |
| Transport, summary | UDP |
| Transport, capture | **TCP** (see §5.1) |

- Service discovery via SOME/IP-SD, standard multicast.
- **Byte order: network (big-endian)** for all fields, per SOME/IP. Do not emit host-order floats
  because both ends happen to be little-endian today.
- Floats are IEEE-754 **32-bit**. Do not send doubles; the Android side stores `FloatArray` and the
  extra precision is discarded after doubling the payload.
- Version the payload. A `uint8` layout version as the first byte of every message costs nothing
  and is the difference between a clean rejection and a plot of garbage when the two sides drift.

---

## 3. The 1 Hz fault event

### 3.1 Cadence

- Publish at **1 Hz ± 100 ms**, continuously, whether or not values changed.
- Publish only while the motor is powered and the acquisition pipeline is healthy. If acquisition
  fails, **stop publishing** — do not publish stale or zeroed values. The head unit treats absence
  as staleness after 3 s and offline after 15 s, which is the correct behaviour; a zero-filled
  message would be rendered as a real reading of zero.

### 3.2 Payload

| Offset | Type | Field | Unit | Notes |
|---|---|---|---|---|
| 0 | `uint8` | `layoutVersion` | — | `1` |
| 1 | `uint8` | `faultType` | — | 0 `NORMAL`, 1 `ELECTRICAL`, 2 `MECHANICAL`, 3 `SENSOR` |
| 2 | `uint8` | `severity` | — | 0 none, 1 advisory, 2 urgent |
| 3 | `uint8` | `flags` | — | bit0 `rulValid`, bit1 `rulPercentValid`, others reserved 0 |
| 4 | `uint32` | `timestampMs` | ms | Monotonic since boot, see §3.5 |
| 8 | `float32` | `rulHours` | h | Ignored unless `rulValid` |
| 12 | `float32` | `rulPercent` | % 0–100 | Ignored unless `rulPercentValid` |

16 bytes plus SOME/IP header. Keep the reserved bits zero so they can be claimed later.

### 3.3 Nothing measured belongs here

Do not add speed, power, current or bus voltage to this event, even if they are cheap to include
and already in memory. The head unit's `MotorTelemetry` has exactly three fields and will discard
anything else at the boundary; adding them here only creates a second, worse answer to questions
the capture already answers properly.

### 3.4 Severity is yours to decide, and to damp

The head unit passes your severity **straight through with no hysteresis** — deliberately, because
you have already classified and a second opinion applied downstream would mean the car showing one
severity while you reported another.

The consequence is yours to own: **if your classifier oscillates near a boundary, the driver sees
the dot, the ring and the alert list flicker.** Apply hysteresis, debouncing or a minimum dwell time
here. A recommended floor is that a severity must hold for 3 consecutive seconds before it is
published as a downgrade; upgrades may be published immediately (escalate fast, de-escalate slowly).

Never publish a severity you do not have evidence for. If the classifier has not run yet, do not
publish the event at all — absence is representable on the other side, a fabricated `OK` is not.

### 3.5 Timestamps

`timestampMs` is monotonic-since-boot on the Pi. The Android side must convert it to its own epoch.
Provide **one** of:

- a boot-time epoch in a separate rarely-changing field or method, or
- absolute UTC milliseconds if the Pi has a reliable synchronised clock.

State which you have chosen here. If neither is available, say so explicitly — the head unit will
fall back to receive time, and the "stale for N seconds" badge becomes approximate under network
delay. That is an acceptable outcome but must be a decision, not a discovery.

---

## 4. Fault classification semantics

The head unit renders the fault type as a label and highlights the matching evidence row on the
card. The mapping between type and evidence is fixed:

| `faultType` | Evidence field the UI emphasises |
|---|---|
| `ELECTRICAL` | `currentImbalancePercent` |
| `MECHANICAL` | `vibrationRmsG` |
| `SENSOR` | `speedTrackingErrorPercent` |

Keep them consistent. If you classify a fault as `MECHANICAL` while the vibration figure looks
unremarkable, the card will show exactly that, side by side — which is useful when it is true and
embarrassing when the two disagree because they were computed from different windows. **Compute
the summary evidence from the same window the classifier used.**

`NORMAL` is a real answer meaning "the classifier ran and found nothing", not "no data". The UI
renders no fault block for it.

---

## 5. The capture method

### 5.1 Why TCP

A capture is ~9.6 MB. Over UDP that is thousands of SOME/IP segments with no retransmission, and a
single lost datagram corrupts a channel silently — the plot would show a discontinuity that looks
like a fault. Use TCP for this method. Configure vsomeip's `reliable` port for the service.

If your vsomeip build cannot carry a payload this size in one response, use §5.5 instead. Do not
solve it by lowering the sample rate or shortening the window without agreement — the window length
is a diagnostic decision, not a transport one.

### 5.2 Request

Empty payload, or optionally:

| Type | Field | Notes |
|---|---|---|
| `uint8` | `layoutVersion` | `1` |
| `float32` | `requestedDurationSec` | Optional; clamp to your supported range and report what you actually captured |

### 5.3 Response

| Type | Field | Notes |
|---|---|---|
| `uint8` | `layoutVersion` | `1` |
| `uint8` | `status` | 0 OK, 1 busy, 2 acquisition failed, 3 not ready, 4 unsupported duration |
| `uint16` | `channelCount` | Must be `12` for layout 1 |
| `uint32` | `sampleCount` | `n`, samples **per channel** |
| `float32` | `sampleRateHz` | Actual rate used |
| `uint64` | `capturedAtMs` | Same time base as §3.5 |
| `float32[]` | `samples` | `channelCount × sampleCount`, **channel-major** |

**Derived figures are optional.** The head unit computes its own from the samples —
`MotorCapture.summarise()`, unit-tested against analytic waveforms — so you do not have to send
average power, RMS current, imbalance, vibration or tracking error. If you send them anyway they
are a useful cross-check, and a disagreement between the two is worth investigating before either
is trusted.

**Channel order, fixed:**

| Index | Channel |
|---|---|
| 0 | `Speed_volt_cmd` |
| 1–3 | `Current_0`, `Current_1`, `Current_2` |
| 4–6 | `Volt_0`, `Volt_1`, `Volt_2` |
| 7 | `DC_bus_volt` |
| 8–10 | `vib_x`, `vib_y`, `vib_z` |
| 11 | `rpm` |

**Channel-major** — all of channel 0, then all of channel 1 — not interleaved per sample. The
receiver fills one `FloatArray` per channel; interleaved data forces a transpose of 2.4 million
floats on the critical path of a user-visible request.

### 5.4 Hard requirements on the payload

- **Every channel must have exactly `sampleCount` samples.** The head unit derives sample time as
  `i / sampleRateHz` and carries no timestamp column; a ragged capture is an index-out-of-bounds in
  the plot. Ragged responses will be rejected outright.
- **SI units, already scaled.** Amps, volts, g, rev/min. Do not send raw ADC counts.
- No NaN or infinity. If a channel is unavailable, fail the request rather than filling it with
  sentinels — a NaN silently destroys the min/max decimation the plot depends on.
- The timestamp column from your CSV is **not** transmitted. Samples are evenly spaced by
  construction; if they are not, say so now, because the entire plotting model assumes it.

### 5.5 If a single response is too large

Acceptable alternative, in preference order:

1. **Chunked method** — a second method returning slice `k` of `m`, with the head unit reassembling.
   Requires a capture ID so slices from two requests cannot be mixed.
2. **File handoff** — write the capture to a file and return a path/URL the head unit fetches over
   HTTP. Simple and debuggable; needs a cleanup policy so the Pi does not fill its rootfs.

Whichever is chosen, it must remain a **single suspending call** from the app's point of view. The
Android interface is `suspend fun requestCapture(): CaptureState`; reassembly is your side's
concern.

### 5.6 Timing

- Target: response complete **within 5 s** of the request. This is visible time — a spinner is on
  screen throughout.
- Hard ceiling: the head unit gives up at **20 s**.
- If a capture is already in progress, return `status = 1 (busy)` immediately rather than queuing.
  The head unit permits only one in-flight request, so busy means something has gone wrong and
  should surface, not silently wait.

### 5.7 Cancellation

The user can close the panel mid-request, which cancels the Android coroutine. Your side may not
learn about this. Ensure an abandoned response cannot wedge the service: no unbounded buffering of
a response nobody will read, and a bounded write timeout.

---

## 6. Acquisition requirements

- **20 kHz per channel, simultaneously sampled** across all 12 channels, or with a known and
  documented skew. Phase relationships are the point of this data; a channel skewed by even a few
  samples changes the apparent phase angle and therefore the imbalance figure.
- At ~160 Hz electrical, 20 kHz is about 125 samples per electrical cycle — ample. The rate is
  driven by the vibration and switching content, not by the fundamental.
- Capture window: **10 s** by default. This is the value compiled into the plot's expectations;
  changing it is fine but must be reported in `sampleCount` / `sampleRateHz` and never assumed.
- The capture should reflect the motor's **current** operating state at the moment of the request.
- Anti-alias filtering ahead of the ADC is assumed. If the signal chain has no filter, note it
  here — the plot's min/max decimation preserves whatever aliasing exists rather than hiding it.

---

## 7. Service lifecycle on Yocto

- Run as a **systemd service**, `Restart=on-failure`, started after networking is up.
- Must tolerate the head unit being absent: offer the service and publish nothing to nobody without
  error spam or unbounded memory growth.
- Must tolerate the head unit disappearing and returning without a restart on either side.
- Log to the journal with rate limiting. A 1 Hz publisher that logs every publication fills a
  Pi's storage.
- Ship a `vsomeip.json` with the service and instance IDs from §2, the reliable (TCP) port for the
  capture method, and the unreliable (UDP) port for the summary event. Both sides' configuration
  must be generated from **one** source of these IDs, not maintained twice.

---

## 8. Interface definition

Provide a **Franca IDL** (`.fidl`) and its deployment (`.fdepl`) as the normative artefact, checked
into this repository alongside these documents. Both sides generate from it. Where CommonAPI's
generated types differ from the byte layouts above, the IDL wins and this document is updated —
but the constraints in §5.4 and §3.4 are behavioural and survive any encoding change.

---

## 9. Testing you are expected to provide

- A **loopback test**: publish a synthetic summary and a synthetic capture from a script, and prove
  a client decodes them with correct units, ordering and lengths.
- A **fault injection harness**: force each of the four fault types and each severity, so the head
  unit's end-to-end behaviour (§9 of `08-...`) can be exercised without a real defect.
- A **link-loss test**: kill the service mid-capture and confirm the client sees a failure rather
  than hanging.
- A **soak test**: 30 minutes of 1 Hz publishing plus a capture every minute, with no memory growth
  and no descriptor leak.
- Confirmation that `sampleCount` is identical across all 12 channels, asserted in the encoder, not
  only in a test.

---

## 10. Open items to resolve before implementation

1. Service/instance IDs and port assignments (§2).
2. Clock alignment strategy (§3.5) — boot epoch, synchronised UTC, or neither.
3. Whether the derived summary values are computed on the Pi (preferred) or on the head unit.
4. Whether a single 9.6 MB response is viable in your vsomeip build, or whether §5.5 applies.
5. Confirmation that the 12 channels are simultaneously sampled, and the skew if not.
6. Whether the drive regenerates, i.e. whether instantaneous power can be negative — the app plots
   and averages signed power either way, but the expected range depends on it.
7. **Pole pairs: 11 or 13.** Reported as one or the other and not confirmed. It sets the electrical
   frequency for a given shaft speed, so it determines how many cycles appear in the plot's 40 ms
   current window; the app currently assumes 13 for its synthetic data only.
8. Confirmation of the motor plate figures: 48 V, 450 W, 750 rpm maximum.
