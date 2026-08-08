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

**The model is not in git.** `app/src/main/assets/car_model.glb` is gitignored, so a fresh clone
renders nothing until you run `./scripts/select-car-model.sh 2` to install
`porsche_mission_e_diag_v2.glb`. Anything below that mentions material names assumes v2.

Regenerate the model itself with:

```bash
blender --background --python vehicle3dModel/tools/prep_car.py -- vehicle3dModel/porsche_mission_e/porsche_mission_e_texture1k.glb vehicle3dModel/porsche_mission_e/porsche_mission_e_diag_v2.glb --motor vehicle3dModel/porsche_mission_e/motor_battery_models/ac-09_induction_motor.glb --battery vehicle3dModel/porsche_mission_e/motor_battery_models/tesla_batterie_pack_v2.glb
```

## What exists

| Area | Where |
|---|---|
| Domain (unchanged seam for Phase 2) | `core/vehicle-data-api`, `core/vehicle-data-fake` |
| 3D car stage, camera, occlusion, rotation | `ui/diagnostics/render/Car3dRenderer.kt` |
| Cutaway zones, faded on focus | `ui/diagnostics/render/CutawayZones.kt` |
| Anchors derived from mesh geometry | `ui/diagnostics/render/HotspotGeometry.kt` |
| Hotspot dots, projection, tappability | `ui/diagnostics/component/HotspotOverlay.kt` |
| Telemetry card, four SignalState renderings | `ui/diagnostics/component/ComponentDetailCard.kt` |
| Health ring, alert list | `ui/diagnostics/component/HealthRing.kt`, `AlertList.kt` |
| Alert dismiss / re-alert rules | `ui/diagnostics/VehicleAlert.kt` (pure, unit-tested) |
| Fake-data panel + live paint picker | `ui/diagnostics/debug/FakeDataControlPanel.kt` |
| Blender asset pipeline | `vehicle3dModel/tools/` — see its README |
| Stage backdrop textures | `vehicle3dModel/tools/make_backdrop.py` |

Every tuning number lives in one place per concern: `Car3dTuning` (camera, occlusion, lighting,
livery, quality), `HotspotGeometry.Tuning` (anchors), `CutawayZones.Tuning` (fade),
`HotspotTokens` (dot appearance), `SeverityThresholds` (domain).

## Colour, and how to change it

`Car3dTuning.Livery` holds five slots — body, rim, rim inner, brake disc, caliper — each with its
own finish. `DEFAULT_LIVERY` is what the car starts in; the debug panel (long-press the health
ring, scroll to "Vehicle paint") writes straight to `Car3dRenderer.livery`, so a colour can be
judged on the real render without a rebuild.

A material can be claimed by a slot even when the shell has no faces carrying it: `Doorsill`
survives only as `Zone_Battery_Doorsill`, because the battery zone's carve takes every sill face.
The suffix match handles that, but it is why probing the shell for a material can come up empty
while the part is plainly on screen.

The five slots exist because the v2 model splits the materials for them. Before that split the
disc and caliper shared one material, and the wheel barrel was painted with the *body* material —
so recolouring the car recoloured its wheels, and a red car got red wheels.

**Never give the rim the body colour.** Tried, rejected: it reads as a toy, and the rim stops
separating from the arch behind it. Accents belong on the caliper, which is where real cars put
them.

## Open items, most consequential first

**1. `FORCE_DOUBLE_SIDED` is still on, and the model is why.** `prep_car.py` recalculates normals
outward at export, but `recalc_face_normals` can only agree a winding *within* a connected island;
an island that is closed and entirely inverted comes out backwards regardless. Enough of the donor
Porsche is built that way that culling leaves holes — with the flag off, the rear wheels show the
motor straight through their spokes. Turning it off needs a model whose winding is right
everywhere, and the check is to look at a rear wheel. Costs a little fill, and it is why
`CutawayZones.Tuning.OPEN_ALPHA` is halved (both faces of a zone contribute).

**2. WCAG AA fails in day mode for the semantic colours.** Measured, not guessed:
`Tokens.Day.success` 2.60:1, `caution` 2.39:1, `critical` 3.85:1 against the light card — all
below the 4.5:1 floor. Every de-emphasised alpha in `ui/diagnostics/` was already raised to the
measured minimum, but these three are in `ui/theme/Tokens.kt`, which other fragment owners share,
so the change was reported rather than made. Passing values are roughly 30% darker: `#16824c`,
`#976c15`, `#cb443c`. The alternative is keeping the diagnostics stage dark in both themes.

**3. Attribution is incomplete.** `Car3dTuning.MODEL_CREDIT` names only the Porsche author. The
derived model is a CC-BY-4.0 derivative of **three** works (Porsche shell, AC-09 motor, Tesla
pack) and must credit all three before reaching users. See `vehicle3dModel/MODEL_LICENSE.md`.

**4. Performance needs re-measuring.** The numbers below predate the lighting change, the
backdrop quad and the v2 model, so treat them as a shape rather than a result: post-processing +
FXAA on gave 43.5% janky frames and a 48 ms p50; off gave 7.9% and 25 ms. Kept **on**, because
without it the door seams visibly stair-step. The emulator's integrated AMD GPU is not the RPi 5's
VideoCore VII, so re-measure on target (spec T13) before deciding. Both constants are in
`Car3dTuning`.

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
- **The battery is framed from ABOVE**, looking down through its cutaway. This reverses an earlier
  decision recorded here — that a floor-mounted pack cannot honestly be shown from above — which
  was correct while the bodywork was opaque and stopped being correct when the cutaway started
  opening. From below the pack is an edge; from above it is a slab with a module grid.
- **Rotation is free only with nothing focused.** A focused view is a framing, not a turntable:
  drag past the bodywork and the eye ends up inside the cabin looking out through the seats. Each
  component clamps to its own `orbitLimitDeg`, and the two orbits are separate values so
  unfocusing returns the overview to the angle the user left it at.
- **The stage backdrop is a quad inside the scene**, not a Compose background. The stage is an
  opaque `SurfaceView`, which punches through the window wherever it draws, so Compose content
  *behind* it is erased. Filament's own alternative, a skybox, needs a KTX cubemap and would feed
  the image back into the lighting.
- **SceneView is pinned to 2.3.0.** It is the last release built against Kotlin 2.0.21. Every 4.x
  needs Kotlin 2.3+, which would force a toolchain bump across build files other owners share.
- **`BackHandler` will not compile here** — `activity-compose` is runtime-only on this classpath.
  Back goes through the fragment's `OnBackPressedDispatcher`.
