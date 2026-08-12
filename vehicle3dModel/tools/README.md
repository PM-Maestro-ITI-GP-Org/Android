# Car GLB preparation for the diagnostics app

Turns a car `.glb` plus component models into an export whose shell the app can fade at
runtime, revealing motor and battery it can recolour by severity.

```bash
blender --background --python tools/prep_car.py -- \
  porsche_mission_e/porsche_mission_e_texture1k.glb \
  porsche_mission_e/porsche_mission_e_diag_v1.glb \
  --motor porsche_mission_e/motor_battery_models/ac-09_induction_motor.glb \
  --battery porsche_mission_e/motor_battery_models/tesla_batterie_pack_v2.glb

python3 tools/check_glb.py porsche_mission_e/porsche_mission_e_diag_v1.glb
blender --background --python tools/preview.py -- \
  porsche_mission_e/porsche_mission_e_diag_v1.glb tools/preview
```

`prep_car.py` is idempotent: it wipes the scene and re-imports every input on each run, so it
always produces the same output from the same inputs. Change a constant and re-run; never edit
the exported GLB by hand.

## Result

| | |
|---|---|
| Nodes | `CarRoot` → `Body_Shell`, `Zone_Motor`, `Zone_Battery`, `Glass`, `Comp_Motor`, `Comp_Battery`, `Wheel_FL/FR/RL/RR` |
| Triangles | 235,201 total — shell 53,474, motor zone 64,453, battery zone 51,138, components 6,000 each |
| Textures | 14, preserved from the source — the car keeps its real paint |
| Bounding box | 1.876 × 1.201 × 4.500 m |
| Validator | all checks pass, 0 warnings |

`alphaMode: BLEND` on `MatBodyShell` came out correct on the first export — Blender 5.2 still
accepts both `blend_method` and `surface_render_method`, and the script sets whichever exists.

## What the previews caught that the validator could not

The bounding-box check passes as long as a component is inside the shell's box. It says nothing
about whether the component is in the *right place*, and three things were wrong:

1. **The motor was under the bonnet.** "Rearward" is meaningless without knowing which way the
   model faces. The front direction is now derived from the `geo_brakes_front` / `geo_brakes_rear`
   meshes, read before they are merged away.
2. **The battery pack came out 46% of its proper size.** Its thin axis was landing on the car's
   width, so the height target became the binding constraint. `align_axes` now rotates a component
   in 90-degree steps until its longest/mid/shortest sit on the car's length/width/height.
3. **The pack was too thin to begin with.** 8% of car height suits a bare slab; a real pack with
   brackets and cooling is ~0.16 m, so the fraction is 15%.

Two earlier failures were structural rather than aesthetic:

- Both source GLBs carry a transform matrix on their root nodes. Re-parenting to `CarRoot`
  dropped it, so a car that measured 4.5 m in Blender exported at **580 m**. Every part is now
  flattened (parent cleared keeping transform, then transforms applied) before anything measures it.
- The AC-09 motor ships with a 447-unit cable that dominated its bounding box. Objects with an
  aspect ratio above 8:1 are dropped as slivers.

## Cutaway zones

The car is **opaque and normally textured**. Only two patches of bodywork are separable and
fadeable: `Zone_Motor` over the rear drive unit and `Zone_Battery` over the floor pack. Fading one
opens a window onto that component while the rest of the car stays untouched — which is the
behaviour the reference cutaway images show, and it is why the shell is no longer ghosted whole.

Zones are cut geometrically (faces within a radius of the component), so the split is
reproducible and needs no hand-picking of panels on this particular mesh. The two radius
constants in `prep_car.py` control how much bodywork each window opens.

Two constraints shape the implementation, both discovered rather than assumed:

- **Filament cannot turn an OPAQUE material transparent at runtime.** Anything that will ever
  fade must be exported BLEND, which is why zones exist as separate objects at all.
- **This Blender's glTF exporter decides `alphaMode` from the alpha VALUE, not `blend_method`.**
  At alpha 1.0 it writes OPAQUE and the cutaway becomes impossible, so zones rest at 0.99. One
  percent of blend is invisible; being wrong here silently disables the whole feature.

Backface culling is on everywhere. Without it a blended surface shows the car's far side through
its near side, which is the silver-ghost look rather than a clean window.

### Still to do — the app half

The asset supports the cutaway; the app does not drive it yet. `Car3dRenderer` needs to find the
`Zone_Motor` / `Zone_Battery` renderables and animate their material alpha when the matching
hotspot is focused. Until then the zones simply sit at 0.99 and the car looks normal.

## Known limitation — read before shipping this model

**The app cannot derive hotspot anchors from this export.** `HotspotGeometry` resolves anchors by
mesh name (`geo_tire*`, `geo_brakes_front*`, `geo_doors*`), and merging the bodywork into a single
`Body_Shell` destroys those names. Confirmed on device:

```
FRONT UNRESOLVED, assuming +length
TIRES: expected 4, got 0, using FALLBACK for all 4
BRAKES: no geo_brakes_* mesh, using FALLBACK
DOORS: no geo_doors_* mesh, using FALLBACK
```

All eight anchors fall back to the estimate table, so the dots sit at plausible-but-approximate
positions rather than on real geometry. This is a direct consequence of the required output
structure, not a bug in the export.

Fixing it is a small change to `HotspotGeometry.Tuning` — teaching it the new names (`Wheel_FL`
… `Wheel_RR`, `Comp_Motor`, `Comp_Battery`) — which was out of scope for this task. Done, the
result would be **better** than the current model: motor and battery would gain real geometry
anchors, which they have never had.

To go back to the textured original: `./scripts/select-car-model.sh 2`

## Not done

- Splitting the body into separate panels (hood, floor, doors). Out of scope; needs human face
  selection on this specific mesh.
- Textures. Every material is replaced with a flat colour, because one unique untextured material
  per object is what makes independent fading and recolouring possible. The car therefore renders
  as neutral grey rather than white paint.
- The shell is never fully opaque. Base-colour alpha is 0.99 to force `alphaMode: BLEND`, and a
  blended material is slightly see-through even at 0.99. Setting it to 1.0 risks the exporter
  writing `OPAQUE`, which would make runtime fading impossible — the wrong trade.
