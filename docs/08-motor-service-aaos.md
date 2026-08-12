# 08 · Requirements — AAOS motor diagnostics service

**Audience:** the engineer implementing the Android-side service that bridges the diagnostics
Raspberry Pi to the MotorGuard IVI app.

**Companion document:** `09-motor-service-someip.md` covers the Pi side. The two must be read
together — every field below has a counterpart there, and the wire encoding is defined only once,
in that document.

The UI is already built and running against a fake source. Your job is to make the real data arrive
through the same seams, so that deleting the fake changes nothing on screen. This document
specifies those seams exactly.

---

## 1. What you are implementing

Two interfaces, both already defined in `core/vehicle-data-api`. Do not change their shapes without
agreeing it here first — the diagnostics screen, the hotspot overlay, the health ring and the alert
list are all written against them.

### 1.1 `VehicleDataSource.motor` — the 1 Hz summary

```kotlin
val motor: StateFlow<SignalState<MotorTelemetry>>
```

A hot `StateFlow`, always readable, never throwing. See §4 for `SignalState` semantics.

### 1.2 `MotorCaptureSource` — the on-demand raw capture

```kotlin
interface MotorCaptureSource {
    suspend fun requestCapture(): CaptureState
}
```

Suspends for as long as acquisition and transfer take. **Must not throw** — a failure is returned
as `CaptureState.Failed(message)`. See §5.

### 1.3 Where it is wired

`ui/diagnostics/VehicleData.kt` is the only file in the app module permitted to name a concrete
data source. Bind your implementations there, replacing `FakeVehicleDataSource` and
`FakeMotorCaptureSource`. Nothing else in the UI may reference your types.

---

## 2. The 1 Hz summary contract

`MotorTelemetry`, in `core/vehicle-data-api/.../Telemetry.kt`:

| Field | Type | Unit | Range | Notes |
|---|---|---|---|---|
| `rpm` | `Int` | rev/min | 0 … 12 000 | Shaft speed, not electrical frequency |
| `powerKw` | `Float` | kW | −30 … 30 | Negative during regeneration; the UI displays it signed |
| `dcBusVolts` | `Float` | V | 0 … 500 | |
| `faultType` | `MotorFaultType` | — | enum | `NORMAL`, `ELECTRICAL`, `MECHANICAL`, `SENSOR` |
| `faultSeverity` | `Severity` | — | enum | `OK`, `CAUTION`, `CRITICAL` |
| `remainingLife` | `RemainingLife?` | — | — | Null when the model has no estimate |
| `capture` | `MotorCaptureSummary?` | — | — | See §2.3 |

`RemainingLife(hours: Float, percent: Float?)` — `hours` is what the driver acts on. `percent` is
optional and is only used to draw a bar; **do not synthesise it** by dividing hours by an assumed
design life. If the Pi does not send a percentage, pass null and the bar is omitted.

### 2.1 Rate and jitter

- Nominal **1 Hz**. The UI is designed for this and does not benefit from faster.
- Emit every message you receive; do not throttle, batch or interpolate.
- Do not emit unchanged values on a timer to "keep it alive". Freshness is carried by
  `SignalState`, not by repetition.

### 2.2 Severity mapping — this is your responsibility

The Pi's severity scale is defined in `09-motor-service-someip.md` §3.4. **You map it into
`Severity` at the boundary.** Do not pass a raw integer up and do not add a second severity concept
to the UI layer.

This matters more than it looks. `Severity` drives the hotspot dot colour, the health-ring score,
the alert list and the card simultaneously. `SeverityResolver.motor()` passes your value through
unchanged and deliberately applies **no hysteresis** — the Pi has already classified, and damping
its answer here would mean the car showing one severity while the unit that made the call reported
another. So a value that oscillates on the wire will oscillate on screen: if the classifier is
noisy, damp it on the Pi, not here.

Required mapping unless renegotiated:

| Pi severity | `Severity` |
|---|---|
| 0 (none) | `OK` |
| 1 (advisory) | `CAUTION` |
| 2 (urgent) | `CRITICAL` |
| unknown value | `CRITICAL`, and log |

An unrecognised severity maps to `CRITICAL`, not `OK`. A new severity level the Pi gained and you
have not been told about is far more likely to mean "worse" than "fine", and silently rendering it
green is the failure mode that matters.

`faultType` maps by ordinal; an unrecognised value maps to `NORMAL` **only if** severity is also 0,
otherwise keep the severity and pick the nearest known type or `NORMAL` — the severity is what
drives safety-relevant colour, the type only labels it.

### 2.3 `capture` on the summary

`MotorCaptureSummary` carries the reduction of the **last requested capture**:

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `capturedAtMs` | `Long` | ms, `System.currentTimeMillis` epoch | When acquisition started |
| `averagePowerKw` | `Float` | kW | Mean over the capture window |
| `currentImbalancePercent` | `Float` | % | Spread of the three phase RMS values |
| `vibrationRmsG` | `Float` | g | RMS of √(x²+y²+z²) |
| `speedTrackingErrorPercent` | `Float` | % | Commanded vs actual speed |

**It must be null until a capture has actually been requested and completed in this session.** The
card renders the block only when non-null; an empty block would imply a request is pending. Do not
populate it from a capture the user did not ask for.

Whether the Pi computes these or you compute them from the capture payload is your choice — see
`09-...` §5.3 for which the Pi offers. Computing them on the Pi is preferred: it already has the
samples in memory, and it avoids a second definition of "imbalance" that could drift from the one
the classifier uses.

---

## 3. What was removed, and why you must not add it back

`MotorTelemetry` previously had `loadPercent` and `tempC`. **This vehicle has no load or
temperature sensor.** Both fields, and their entries in `SeverityThresholds`, have been deleted.

Do not reintroduce either as an estimate — a computed "load %" derived from current, or a
temperature model, is a number the driver will read as a measurement. If the Pi ever gains real
sensors for these, add them as new fields with their own thresholds and raise it here first.

---

## 4. `SignalState` — freshness semantics

Every emission must be one of four states. These are not stylistic; the UI's honesty rules depend
on them.

| State | When to emit | UI behaviour |
|---|---|---|
| `Loading` | Subscribed, nothing received yet | Skeleton placeholders |
| `Live(data, timestampMs)` | A fresh message arrived | Values shown normally |
| `Stale(lastData, lastTimestampMs)` | Messages stopped, last value still meaningful | Values dimmed, explicit "stale" badge with age |
| `Offline` | Source unreachable | **No numbers at all** — "No data" |

### 4.1 Required timings

- **Stale after 3 s** without a message (3 missed cycles at 1 Hz).
- **Offline after 15 s** without a message, or immediately on transport disconnect (socket closed,
  service unavailable, Pi unreachable).
- On reconnect, go straight to `Live` on the first message. Do not pass through `Loading`.

### 4.2 `Offline` carries no payload — structurally

`SignalState.Offline` is `SignalState<Nothing>`. It **cannot** hold a last value, and the card's
offline branch never invokes its content lambda. This is deliberate: an offline signal renders no
number, not a greyed one, not a dash. Do not work around it by emitting
`Stale` forever when you know the source is gone — a driver reading a dimmed-but-present figure
believes the vehicle reported it.

### 4.3 `timestampMs`

The time the sample was **captured on the Pi**, converted to the Android epoch, not the time you
received it. If clock alignment across the two devices is not solved, use receive time and say so
here — but then the stale badge's age is a lie under network delay, which is a decision to make
explicitly rather than by accident.

---

## 5. The capture request

### 5.1 Shape

```kotlin
suspend fun requestCapture(): CaptureState
```

Returns exactly one of:

- `CaptureState.Ready(capture: MotorCapture)`
- `CaptureState.Failed(message: String)`

`Requesting` and `Idle` are set by the ViewModel, not by you.

### 5.2 The payload

`MotorCapture` holds parallel `FloatArray`s, one per channel — **not** a list of sample objects. At
20 kHz for 10 s that is 200 000 samples across 12 channels; as objects it is 2.4 million
allocations and enough GC pressure to visibly stutter the plot it feeds.

| Field | Shape | Unit |
|---|---|---|
| `speedVoltCmd` | `FloatArray(n)` | V |
| `current` | `Array(3) { FloatArray(n) }` | A |
| `voltage` | `Array(3) { FloatArray(n) }` | V |
| `dcBusVolts` | `FloatArray(n)` | V |
| `vibration` | `Array(3) { FloatArray(n) }` | g |
| `rpm` | `FloatArray(n)` | rev/min |
| `capturedAtMs` | `Long` | ms epoch |

Constraints:

- **All arrays must be the same length `n`.** The UI derives sample time as `i / SAMPLE_RATE_HZ`
  and does not carry a timestamp array. A ragged capture will index out of bounds.
- `MotorCapture.SAMPLE_RATE_HZ` is `20_000f` and is compiled in. If the Pi's rate is configurable,
  it must be reported and this constant must become a field — raise it before you ship a mismatch,
  because nothing will detect it: the plot will simply show the wrong time axis.
- Channel order within `current`, `voltage` and `vibration` is phase A/B/C and x/y/z respectively,
  and must be stable.
- Values are **already in SI units**. Do not pass raw ADC counts and expect the UI to scale them.

### 5.3 Timing and cancellation

- Budget: acquisition + transfer **under 5 s**. The UI shows a pending state throughout, so this is
  visible time, not free time.
- **Time out at 20 s** and return `Failed`. A spinner that never resolves is the worst rendering of
  a failure.
- The call runs in `viewModelScope`. It **must be cancellable**: if the user closes the panel
  mid-request, the coroutine is cancelled and any transfer you started must be abandoned or allowed
  to complete harmlessly. Do not leak a thread or leave a half-read socket.
- Only one request is in flight at a time; the ViewModel enforces this. You do not need to queue.

### 5.4 Do the work off the main thread

`VehicleData` runs its scope on `Dispatchers.Main.immediate`. Deserialising ~10 MB and filling
arrays on that thread will drop frames on the 3D car stage. Use `withContext(Dispatchers.Default)`
or `Dispatchers.IO` for parsing, as `FakeMotorCaptureSource` does.

### 5.5 Failure messages are shown to the user

`Failed.message` is rendered verbatim in the panel. Write a sentence, not an exception:

- Good: "The diagnostics unit did not respond." / "Capture returned no samples."
- Bad: `java.net.SocketTimeoutException: Read timed out`

Distinguish at least: unreachable, timed out, malformed/empty response, and rejected-by-Pi.

---

## 6. Threading and lifecycle

- `VehicleData` holds a process-lifetime `CoroutineScope` on `Dispatchers.Main.immediate` and is
  deliberately never cancelled. Your source is constructed there and lives for the process.
- All `StateFlow` emissions must be main-thread confined, matching the existing contract. Do IO on
  a background dispatcher and hop back to emit.
- Your source must survive the diagnostics fragment being destroyed and recreated on every tab
  switch. Do not tie the connection to a fragment or ViewModel lifecycle.
- Reconnection is your responsibility and must be automatic with backoff. `VehicleDataSource.reconnect()`
  exists as a manual nudge; implement it, but do not rely on the user pressing anything.

---

## 7. Permissions, manifest and build files

The app's `AndroidManifest.xml` and all `build.gradle.kts` files are currently **off-limits to UI
work** and are shared with other fragment owners. Your service will need changes to both
(permissions, possibly a `<service>`, dependencies for CommonAPI/vsomeip bindings).

Raise these as a separate, reviewed change. Specifically expect to need:

- Network permissions, and any SELinux policy if you run as a native daemon.
- If you use a native library: the JNI packaging, ABI filters, and a decision about whether
  vsomeip is bundled or provided by the platform image.
- Note the project is pinned to **Kotlin 2.0.21** because SceneView is pinned to 2.3.0. Do not
  introduce a dependency that forces Kotlin 2.3+ without agreeing a toolchain bump across the
  whole project.

---

## 8. Testing you are expected to provide

- **A fake that is not the UI's fake.** `FakeVehicleDataSource` exists for the UI; your transport
  needs its own test double at the wire level.
- Round-trip tests: a capture serialised by the Pi's encoder and decoded by you must produce arrays
  of equal length with values within float tolerance.
- State-machine tests for §4.1: message stops → `Stale` at 3 s → `Offline` at 15 s → `Live` on
  reconnect, driven by a virtual clock rather than real delays.
- A malformed-payload test: truncated arrays, ragged lengths, NaN values. **Ragged lengths must be
  rejected as `Failed`, not passed to the UI**, where they become an index-out-of-bounds in the
  plot.
- Severity mapping table (§2.2) including the unknown-value case.

---

## 9. Acceptance criteria

The work is done when, with the fake source deleted:

1. Focusing the motor shows speed, power and DC bus updating at ~1 Hz from the real Pi.
2. Inducing each fault type on the Pi changes the fault label, the hero severity colour, the
   hotspot dot, the health ring and the alert list — all together, with no disagreement between
   them.
3. Unplugging the Pi produces "No data" within 15 s and no numbers of any kind.
4. Reconnecting restores live values with no app restart.
5. `Engineering insights` returns a plottable capture within 5 s, and the plot's time axis matches
   the real capture duration.
6. Killing the Pi mid-request shows the failure message, not a spinner.
7. The app survives 30 minutes of tab switching with no growth in memory or sockets.
