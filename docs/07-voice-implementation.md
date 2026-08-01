# 07a · Voice overlay — implementation notes (owner D)

Companion to [07-voice.md](07-voice.md), which is the spec. This is what actually
landed, what's verified, and what still needs doing.

## Files

```
MotorGuardApp/app/src/main/
├── java/com/motorguard/ivi/ui/voice/
│   ├── VoiceOverlayService.kt   VoiceInteractionService — always-on host, owns wake word
│   ├── VoiceSessionService.kt   mints a session when triggered
│   ├── VoiceOverlaySession.kt   one interaction: STT → core → TTS, AudioFocus, routing
│   ├── VoiceOverlayUi.kt        Compose bottom bar: orb · waveform · transcript · chips
│   ├── VoiceEngine.kt           JNI bridge to the C++ reasoning core
│   ├── WakeWord.kt              WakeWordDetector port + openWakeWord/ONNX impl
│   └── VoiceTrigger.kt          rail mic button path (manual trigger)
├── cpp/                         native core + CMake — see cpp/README-native.md
├── res/xml/interaction_service.xml
└── assets/wakeword/             .onnx models go here (not committed)
```

## Architecture — why it's split this way

**All reasoning is C++ in `cpp/assistant-core/`, shared byte-for-byte with the
Linux build.** Kotlin owns only the platform edges: microphone, STT, TTS, overlay
UI, intent routing. Nothing in Kotlin decides what a fault means or how urgent it
is.

That matters for one specific reason. Severity is produced by
`DiagnosticsEngine`'s rules, and those rules **may only ever raise it** —
escalation is `std::max` over an ordered enum, so nothing downstream can talk the
driver out of stopping. A fault code alone gives a base severity; live sensor data
can push it higher. `P0217` is "urgent"; `P0217` with `coolant_temp_c = 121`
becomes "pull over now".

Verified on the host before shipping (`faultCount = 14`):

| Input | Reply |
|-------|-------|
| push `P0217` @ 121 °C | *"…This is urgent. The engine is critically hot. Pull over safely…"* (announced unprompted) |
| "is it serious" | *"No — you should stop as soon as it's safe… I've raised the urgency because of the current sensor readings."* |
| "am I okay to get home in this thing" | same — the scoring matcher handles free phrasing, not fixed commands |
| "somewhere I can get this looked at" | lists the three nearest service stations |
| "what does P0420 mean" | catalytic-converter explanation, correctly marked minor |

## Wiring it up on a device

```bash
# 1. become the selected assistant
adb shell settings put secure voice_interaction_service \
  com.motorguard.ivi/.ui.voice.VoiceOverlayService

# 2. mic permission — AAOS is multi-user, grant against the driver user
adb shell pm list users
adb shell pm grant --user 10 com.motorguard.ivi android.permission.RECORD_AUDIO

# 3. watch it work
adb logcat -c && adb logcat | grep MotorGuardVoice
```

Trigger with the wake word, the rail mic button, or `input keyevent KEYCODE_ASSIST`.

Emulator note: use a **Google APIs** automotive image, not **Google Play** — Play
images block third-party apps from holding the assistant role.

## Known gaps — read before trusting this

1. **The wake phrase doesn't match the spec.** `strings.xml` says *"Hey Motor
   Guard"*; the trained model in `assets/wakeword/` answers to **"Hey Vega"**.
   Retraining for the spec phrase is a Colab job (~1 h GPU) — change
   `WAKE_MODEL_FILE` in `WakeWord.kt` when the new `.onnx` lands. Until then the
   string and the behaviour disagree, deliberately and visibly.

2. **`OnnxWakeWordDetector` is unverified on device.** The three-model pipeline
   (mel → embedding → wake) is written to openWakeWord's reference geometry
   (32 mels · 76-frame window · 96-d embedding · 16-embedding history, mel scaled
   `/10 + 2`), but this Kotlin port has never run against real models. On first
   run it logs every tensor's actual shape — compare those against the constants,
   and check the scores against the Python `test_wake.py` on the same WAV before
   relying on it. If it fails it disables itself; the mic button keeps working.

3. **STT is Android's, not offline yet.** `SpeechRecognizer` may need network and
   is flaky on bare emulators. The offline path (Whisper or Vosk via the same JNI
   pattern already proven here) is the next real task, and it's what the project's
   offline requirement actually demands.

4. **No faults arrive from the vehicle yet.** `VoiceEngine.pushFault()` is the
   entry point; nothing calls it. When `CarDataRepository` is reading VHAL, feed
   DTCs and predictive codes in there — the core already treats predictive
   forecasts as first-class (`FaultSource::Predicted`), so "nothing's wrong yet,
   but your brakes need service soon" works without new plumbing.

5. **Chips are routing-only.** They bring the right tab forward via
   `MainActivity.EXTRA_TAB`; they don't yet perform the action (start playback,
   set climate). Owners B and E will need small entry points for that.

## Rules kept

- Colors come from `Tokens` — the overlay pins to the night palette on purpose,
  since it floats over arbitrary content and must stay legible in both themes.
- Transform/opacity animation only (orb scale, bar heights). No animated blur or
  shadow, per the RPi 5 perf budget.
- Tap-outside dismisses and releases AudioFocus; focus is
  `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, so media ducks rather than stopping.
- No launcher icon, not a tab, not user-launchable except through the assist path.
