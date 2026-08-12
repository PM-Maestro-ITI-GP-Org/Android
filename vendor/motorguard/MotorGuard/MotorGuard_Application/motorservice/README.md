# Motor diagnostics link — SOME/IP client (AAOS side)

The head unit's half of the motor diagnostics service specified in the app branch's
`docs/09-motor-service-aaos.md` (Android side) and `docs/10-motor-service-someip.md` (wire
contract). It replaces `FakeVehicleDataSource.motor` and `FakeMotorCaptureSource` with the real
thing; nothing else in the diagnostics screen changes, and nothing else in the app knows this
exists.

```
diagnostics unit                          head unit (this)
────────────────                          ────────────────
offer service          ──SD/multicast──▶  discovery              motor_link.cpp
                       ◀────subscribe───  eventgroup 0x0001
1 Hz fault event       ────UDP─────────▶  MotorFreshness   ─┐
                                                            ├─▶ SignalState<MotorTelemetry>
capture method 0x0002  ◀───TCP request──  SomeIpMotorCapture┘         │
~9.6 MB of samples     ────TCP response▶  Source                      ▼
                                                             DiagnosticsScreen
```

## Why there is no vsomeip here

vsomeip and CommonAPI need Boost, and AOSP ships no Boost. Using them would mean vendoring
Boost into the tree with Android.bp files for four compiled libraries and then owning that, for
a client that speaks exactly two message types. SOME/IP exists so that interoperation happens at
the wire: the diagnostics unit keeps its vsomeip/CommonAPI stack and this speaks the same bytes
at it.

The cost of that choice is honest: nothing generates this code from an IDL, so **the byte
layouts below are the contract**, and if the other side changes one, this side does not find out
at compile time. That is what the host tests and the layout-version byte are for.

## Identity

`docs/10` §2 left the service ID blank and §10 lists assigning it as the first thing to settle.
This is the assignment, and it is what `MotorLinkConfig` compiles in:

| Item | Value | Note |
|---|---|---|
| Service ID | `0x1241` | Next to the QNX pair's `MotorDataService` (`0x1240`), which is a different service |
| Instance ID | `0x0001` | |
| Major / minor | `1` / `0` | A major mismatch is ignored outright, not tolerated |
| Summary event | `0x8001` | UDP |
| Eventgroup | `0x0001` | |
| Capture method | `0x0002` | TCP |
| Client ID | `0x1341` | Ours; only has to be unique among this service's clients |
| SD multicast | `224.244.224.245:30490` | Group matches the QNX bus; **port is the SOME/IP default, theirs is 30491** |
| Default static ports | UDP 30502 / TCP 30503 | Only used when discovery is bypassed |

> **The SD port is the one number most likely to be wrong on first contact.** 30490 is the
> standard; the QNX side's `vsomeip_multicast.json` uses 30491. Whichever the bus settles on,
> both ends must agree — set `persist.motorguard.someip.sd_port` rather than editing code.

## Wire contract

Big-endian throughout, IEEE-754 32-bit floats, standard 16-byte SOME/IP header. Every payload
starts with a `uint8` layout version, currently `1`; anything else is refused rather than
interpreted.

**Fault event** (`docs/10` §3.2) — 16 bytes, offsets fixed:

| Offset | Type | Field |
|---|---|---|
| 0 | `u8` | layoutVersion = 1 |
| 1 | `u8` | faultType — 0 NORMAL, 1 ELECTRICAL, 2 MECHANICAL, 3 SENSOR |
| 2 | `u8` | severity — 0 none, 1 advisory, 2 urgent |
| 3 | `u8` | flags — bit0 rulValid, bit1 rulPercentValid |
| 4 | `u32` | timestampMs (sender's monotonic clock) |
| 8 | `f32` | rulHours |
| 12 | `f32` | rulPercent |

**Capture request** (`docs/10` §5.2) — 5 bytes: `u8` layoutVersion, `f32` requestedDurationSec.

**Capture response** (`docs/10` §5.3) — a header followed by `channelCount × sampleCount` floats,
channel-major, in the order `Speed_volt_cmd, Current_0..2, Volt_0..2, DC_bus_volt, vib_x/y/z, rpm`.

`docs/10` lists the header's fields without offsets, which leaves the `u64` either packed at 12 or
aligned at 16 depending on whose struct wrote it — a difference that yields a plausible sample
count and a plot of noise rather than an error. **Both are accepted**, and the arbiter is
arithmetic: the payload must be exactly `headerSize + channelCount × sampleCount × 4` bytes, which
a wrong guess misses by megabytes. Which one arrived is logged per capture, so a disagreement
shows up in a bug report instead of on the screen.

| | packed (20 B) | aligned (24 B) |
|---|---|---|
| layoutVersion `u8` | 0 | 0 |
| status `u8` | 1 | 1 |
| channelCount `u16` | 2 | 2 |
| sampleCount `u32` | 4 | 4 |
| sampleRateHz `f32` | 8 | 8 |
| capturedAtMs `u64` | 12 | 16 |
| samples | 20 | 24 |

Refused at the boundary, never passed to the UI: a ragged sample block, any NaN or infinity
(`docs/10` §5.4 — one of either silently flattens the plot's min/max decimation), a channel count
that is not 12, and a sample rate more than 0.5 % from the 20 kHz the plot's time axis is built on.

## Decisions this side made

**Severity maps at the boundary and is not damped** (`docs/09` §2.3). 0/1/2 → OK/CAUTION/CRITICAL;
**an unrecognised value maps to CRITICAL**, because a level the unit gained and we were not told
about is far likelier to mean "worse" than "fine". An unrecognised fault *type* falls back to
NORMAL for the label only — the severity it arrived with is kept, since the colour carries the
safety meaning and the type only names it.

**Timestamps are local receive time, not the sender's** (`docs/09` §4.3, `docs/10` §3.5). The
unit's clock is monotonic-since-boot and no boot epoch is exchanged, so converting it would mean
inventing an offset; used directly it would render as 1970 on the card. The consequence, stated
rather than discovered: the stale badge's age includes network delay, invisible at 1 Hz over a
local link and wrong if the two devices were ever far apart. Publishing a boot epoch from the unit
is what would fix it properly.

**Freshness is a wall-clock property**, re-evaluated every 250 ms whether or not anything arrives:
`Live` → `Stale` at 3 s → `Offline` at 15 s, and `Offline` immediately when the transport itself
goes (a stop offer, or discovery losing the peer). A reconnect goes straight back to `Live` on the
first message without passing through `Loading`.

**Only the motor comes off this link.** The other five signals — battery, tyres, brakes, doors,
metrics — keep coming from whatever source was there before, because this vehicle has no such
sensors and `docs/09` describes one signal and one method and stops. Forcing them Offline would
blank most of the screen to make a point about data this link was never going to carry.

## Layout

```
include/motorguard/someip/
  wire.h        header framing + both payload layouts   ─┐ no sockets, no state,
  sd.h          service discovery, client half only     ─┘ host-testable
  motor_link.h  the link: sockets, thread, capture
src/
  wire.cpp  sd.cpp  motor_link.cpp  jni.cpp
java/com/motorguard/ivi/data/vehicle/someip/
  MotorLinkNative.kt          the entire JNI surface, and nothing else
  MotorLinkConfig.kt          identity + endpoints + system-property overrides
  MotorEventMapping.kt        wire integers → MotorTelemetry
  MotorFreshness.kt           Loading/Live/Stale/Offline, clock-injected
  SomeIpMotorLink.kt          owns the native handle, publishes the motor flow
  SomeIpMotorCaptureSource.kt the suspend capture, and every failure sentence
  SomeIpVehicleData.kt        the decorator + the factory VehicleData calls
test/wire_test.cpp            16 host tests over the two layers above the socket
vehicledata-someip.patch      wires it into the app's VehicleData.kt (deploy.sh applies it)
```

Threads: one background thread owns discovery and the event socket; a capture runs on the
caller's IO thread over its own short-lived TCP connection. Nine and a half megabytes has no
business on the thread that has to stay responsive to a 1 Hz heartbeat, and a request that fails
or is abandoned cannot wedge the event path. Cancellation crosses the boundary: closing the panel
cancels the coroutine, which shuts the socket down, and the blocked read returns promptly instead
of holding an IO thread for the full 20 s ceiling.

The link is created once, from `VehicleData`'s process-lifetime scope, and never cancelled — it
has to survive the diagnostics fragment being destroyed and rebuilt on every tab switch
(`docs/09` §6).

## Why the Kotlin lives here and not on the app branch

These sources are added to the app module by this drop-in's `Android.bp`, and `VehicleData.kt` is
rewired by `vehicledata-someip.patch` at deploy time. They are not on the app branch because
there is no Gradle path to `libmotorguardsomeip.so`, so a Gradle build could compile the Kotlin
and then fail to load the library at runtime. Keeping both halves in the tree that can build both
means the emulator build keeps the fake, unchanged and unaware.

If the app branch ever grows a Gradle/CMake path for the native library, this whole directory
moves there and the patch goes away. Nothing here depends on being in the drop-in except the
patch itself.

## Configuration

Identity is compiled in — both ends must agree exactly, and a per-device override is a number
that will differ per device. Endpoints are the opposite case and can be set before the app starts:

```bash
adb shell setprop persist.motorguard.someip.host 10.0.2.2     # static peer; bypasses discovery
adb shell setprop persist.motorguard.someip.udp_port 30502
adb shell setprop persist.motorguard.someip.tcp_port 30503
adb shell setprop persist.motorguard.someip.sd_addr 224.244.224.245
adb shell setprop persist.motorguard.someip.sd_port 30491
```

The static-host escape hatch earns its place on the first bring-up: multicast is what a bench
switch, a hypervisor bridge or a Wi-Fi link drops silently, and "it works pointed straight at the
unit but not through discovery" is a diagnosis rather than a shrug.

## Testing

```bash
m motorguard_someip_test && out/host/linux-x86/bin/motorguard_someip_test
adb logcat -s MotorGuardLink
```

The host tests cover framing, both capture layouts, the ragged and NaN refusals, the SD entry and
option encoding, and offer parsing including the TTL-0 stop. They exist because everything they
cover fails *quietly*: a malformed subscribe is ignored rather than rejected, and the only symptom
is a link that never comes up.

What they do not cover, and what a peer is needed for: discovery against a real vsomeip offer,
subscription renewal across a peer restart, a 9.6 MB transfer, and the freshness timings
end-to-end. `MotorFreshness` and `MotorEventMapping` are pure and clock-injected precisely so they
can be unit-tested when this moves somewhere with a JVM test harness — the drop-in has none.

## Still open

1. **Nothing here has been run against a real peer**, or built by Soong. The C++ compiles clean
   under `-Wall -Wextra` and the Kotlin type-checks against `vehicle-data-api`, both on the host.
2. **SD port** — 30490 here, 30491 on the QNX bus. §2 above.
3. **Clock alignment** — receive time is the documented fallback. A boot epoch from the unit, or a
   synchronised UTC clock, would make the stale badge's age exact.
4. **The peer does not exist yet.** `docs/09`/`docs/10` are requirements, and the service on the
   diagnostics unit is someone else's build. Until it offers `0x1241`, this link sits in `kDown`
   and the motor tile reads "No data" — which is the specified behaviour, not a failure.
5. **SELinux** — untested. The app is a normal client socket user, so `untrusted_app`/`priv_app`
   network access should cover it, but multicast join on a platform app is worth watching for a
   denial in `dmesg` on first boot.
