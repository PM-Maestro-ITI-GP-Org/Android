# 05 · Diagnostics — Phase 1 status

Companion to `05-diagnostics.md` (the design) and
`implementationPLan/README-phase1-ui-brief.md` (the build spec). This file records where the work
actually stands, so a new session can pick it up without re-deriving anything.

Branch: `feature/diagnostic`. All Phase 1 UI steps are done and committed.

## Build and run

```bash
cd MotorGuardApp
JAVA_HOME=/snap/android-studio/236/jbr ./gradlew :app:assembleDebug :app:testDebugUnitTest :core:vehicle-data-api:test
~/Android/Sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

System `java` is 11 and will not work — AGP needs 17+, hence the JBR path. The automotive AVD
exposes two displays, so `screencap` needs `-d <displayId>` or it prints a warning ahead of the
PNG bytes. A physical phone may also be attached; target the emulator explicitly with `-s`.

29 unit tests: `SeverityEvaluatorTest` (11), `SignalStateTest` (3), `TireTempSeverityTest` (5),
`AlertTrackingTest` (10).

## What exists

| Area | Where |
|---|---|
| Domain (unchanged seam for Phase 2) | `core/vehicle-data-api`, `core/vehicle-data-fake` |
| 3D car stage, camera, occlusion, rotation | `ui/diagnostics/render/Car3dRenderer.kt` |
| Anchors derived from mesh geometry | `ui/diagnostics/render/HotspotGeometry.kt` |
| Hotspot dots, projection, tappability | `ui/diagnostics/component/HotspotOverlay.kt` |
| Telemetry card, four SignalState renderings | `ui/diagnostics/component/ComponentDetailCard.kt` |
| Health ring, alert list | `ui/diagnostics/component/HealthRing.kt`, `AlertList.kt` |
| Alert dismiss / re-alert rules | `ui/diagnostics/VehicleAlert.kt` (pure, unit-tested) |
| Fake-data debug panel (long-press the ring) | `ui/diagnostics/debug/FakeDataControlPanel.kt` |
| Blender asset pipeline | `vehicle3dModel/tools/` — see its README |

Every tuning number lives in one place per concern: `Car3dTuning` (camera, occlusion, quality),
`HotspotGeometry.Tuning` (anchors), `HotspotTokens` (dot appearance), `SeverityThresholds` (domain).

## Open items, most consequential first

**1. The app does not drive the cutaway zones.** `porsche_mission_e_diag_v1.glb` contains
`Zone_Motor` and `Zone_Battery` — patches of bodywork exported BLEND so they can fade, opening a
window onto the component beneath while the rest of the car stays opaque and textured. Nothing in
`Car3dRenderer` touches them yet, so the car simply looks normal. Wiring it means finding those
renderables and animating their material alpha when the matching hotspot is focused.

**2. Anchors regress with the new model, and it is currently installed.** `HotspotGeometry`
resolves anchors from mesh names (`geo_tire*`, `geo_brakes_front*`, `geo_doors*`), which the
Blender merge destroys. Confirmed on device — all eight fall back to the estimate table:

```
TIRES: expected 4, got 0, using FALLBACK for all 4
BRAKES / DOORS: no geo_* mesh, using FALLBACK
```

Teaching `HotspotGeometry.Tuning` the new names (`Wheel_FL`…`Wheel_RR`, `Comp_Motor`,
`Comp_Battery`) fixes it and would make motor and battery **better** than today, since those two
have only ever had estimated positions. Revert meanwhile with `./scripts/select-car-model.sh 2`.

**3. WCAG AA fails in day mode for the semantic colours.** Measured, not guessed:
`Tokens.Day.success` 2.60:1, `caution` 2.39:1, `critical` 3.85:1 against the light card — all
below the 4.5:1 floor. Every de-emphasised alpha in `ui/diagnostics/` was already raised to the
measured minimum, but these three are in `ui/theme/Tokens.kt`, which other fragment owners share,
so the change was reported rather than made. Passing values are roughly 30% darker: `#16824c`,
`#976c15`, `#cb443c`. The alternative is keeping the diagnostics stage dark in both themes.

**4. Attribution is incomplete.** `Car3dTuning.MODEL_CREDIT` names only the Porsche author. The
derived model is a CC-BY-4.0 derivative of **three** works (Porsche shell, AC-09 motor, Tesla
pack) and must credit all three before reaching users. See `vehicle3dModel/MODEL_LICENSE.md`.

**5. Performance is a signal, not a verdict.** Measured across four consecutive focus transitions
on the emulator: post-processing + FXAA on gives 43.5% janky frames and a 48 ms p50; off gives
7.9% and 25 ms. Kept **on**, because without it the door seams visibly stair-step. The emulator's
integrated AMD GPU is not the RPi 5's VideoCore VII, so re-measure on target (spec T13) before
deciding. Both constants are in `Car3dTuning`.

## Decisions worth not re-litigating

- **Dismissing an alert changes nothing but the row.** Not the dot, not the ring. The car stays
  honest. Dismissals record the severity they were made at, so escalation re-alerts.
- **Offline renders no numbers at all** — not a dash, not a greyed last value. Enforced by the
  type: `SignalState.Offline` carries no payload and the card's offline branch never invokes the
  content lambda.
- **Occluded far-side dots stay tappable only when alerting**, and only when no near-side dot is
  within one touch target. An alert you can see but not touch is a dead end; a dot that steals a
  tap from the one in front of it is the defect that made them untappable originally.
- **Occlusion is computed live from the camera every frame**, never baked, specifically so touch
  rotation works — which it now does.
- **SceneView is pinned to 2.3.0.** It is the last release built against Kotlin 2.0.21. Every 4.x
  needs Kotlin 2.3+, which would force a toolchain bump across build files other owners share.
- **`BackHandler` will not compile here** — `activity-compose` is runtime-only on this classpath.
  Back goes through the fragment's `OnBackPressedDispatcher`.
