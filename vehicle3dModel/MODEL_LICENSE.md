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
