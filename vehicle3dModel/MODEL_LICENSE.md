# 3D vehicle model licences

Models under this directory are third-party assets. `scripts/select-car-model.sh` installs the
selected one into `MotorGuardApp/app/src/main/assets/car_model.glb` and writes a
`car_model.source.txt` beside it recording which one is currently in the build.

## porsche_mission_e

| | |
|---|---|
| **Title** | Porsche Mission E |
| **Author** | kevin (ケビン) — https://sketchfab.com/sohyalebret |
| **Source** | https://sketchfab.com/3d-models/porsche-mission-e-0265b34846cc4380bfb6f5d73203f063 |
| **Licence** | CC-BY-4.0 — http://creativecommons.org/licenses/by/4.0/ |

**CC-BY-4.0 requires attribution wherever the work is used.** This is not optional and does not
end at this file: the app itself credits the author on the Diagnostics screen, under the car
stage. If the model is swapped for one under a different licence, update both this file and the
in-app credit — `Car3dTuning.MODEL_CREDIT` in `Car3dRenderer.kt` is the single string the UI reads.

Files: `porsche_mission_e_texture1k.glb` (15 MB) and `_texture2k.glb` (17 MB) differ only in
texture resolution — identical geometry, 202,313 vertices. `porsche-mission-e_fbx.zip` is the
original FBX distribution and is not used by the build.

## motor_battery_models/ — inputs to the diagnostics build

Not vehicles; `tools/prep_car.py` consumes these and `scripts/select-car-model.sh` deliberately
skips the directory.

| File | Title | Author | Licence |
|---|---|---|---|
| `ac-09_induction_motor.glb` | AC-09 induction Motor | (see the file's `asset.extras`) | CC-BY-4.0 |
| `tesla_batterie_pack_v2.glb` | Tesla Batterie Pack V2 | (see the file's `asset.extras`) | CC-BY-4.0 |
| `electric_motor.glb` | Electric Motor | (see the file's `asset.extras`) | CC-BY-4.0 — currently unused |

## porsche_mission_e_diag_v1.glb — derived, and the one the app should ship

Built by `tools/prep_car.py` from the 1k Porsche plus the AC-09 motor and the Tesla pack. Because
it is a derivative of three CC-BY-4.0 works, **it inherits the attribution obligation of all three**
— crediting only the Porsche author is not sufficient.

Regenerate with:

```
blender --background --python tools/prep_car.py -- \
  porsche_mission_e/porsche_mission_e_texture1k.glb \
  porsche_mission_e/porsche_mission_e_diag_v1.glb \
  --motor porsche_mission_e/motor_battery_models/ac-09_induction_motor.glb \
  --battery porsche_mission_e/motor_battery_models/tesla_batterie_pack_v2.glb
```

**Outstanding:** `Car3dTuning.MODEL_CREDIT` in `Car3dRenderer.kt` still names only the Porsche
author. It must list all three before this model ships in a build that reaches users.
