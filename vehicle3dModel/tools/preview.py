#!/usr/bin/env python3
"""Render preview PNGs of a prepared car GLB.

    blender --background --python preview.py -- <file.glb> <out_dir> [--alpha 0.2]

Four 512x512 renders: the car as the driver normally sees it, then each cutaway zone opened in
turn and framed on the component it reveals, then both open from below.

The cutaway views are the point. A bounding-box check can prove a component sits inside the
shell; only a render can show whether the window over it reads as a window into a normal-looking
car, rather than the whole vehicle turning to glass.
"""
import math
import sys

import bpy
from mathutils import Vector

ZONES = ("Zone_Motor", "Zone_Battery")
RESTING_ALPHA = 0.99   # matches prep_car.blendify(); see there for why it is not 1.0
RES = 512


def log(msg):
    print(f"[preview] {msg}", flush=True)


def args():
    a = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if len(a) < 2:
        raise SystemExit("usage: ... -- <file.glb> <out_dir> [--alpha 0.2]")
    alpha = 0.2
    if "--alpha" in a:
        alpha = float(a[a.index("--alpha") + 1])
    return a[0], a[1].rstrip("/"), alpha


def bounds(objs):
    lo = Vector((1e30,) * 3)
    hi = Vector((-1e30,) * 3)
    for o in objs:
        if o.type != "MESH":
            continue
        for c in o.bound_box:
            w = o.matrix_world @ Vector(c)
            for k in range(3):
                lo[k] = min(lo[k], w[k])
                hi[k] = max(hi[k], w[k])
    return lo, hi


def setup_world():
    # Engine id differs across versions: EEVEE Next is BLENDER_EEVEE_NEXT in 4.2-4.5 and back to
    # plain BLENDER_EEVEE in 5.x. Pick whichever this build advertises.
    available = bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items.keys()
    for candidate in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        if candidate in available:
            bpy.context.scene.render.engine = candidate
            break
    log(f"engine {bpy.context.scene.render.engine}")
    bpy.context.scene.render.resolution_x = RES
    bpy.context.scene.render.resolution_y = RES
    world = bpy.data.worlds.new("W")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.05, 0.06, 0.08, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 1.5
    bpy.context.scene.world = world
    for pos, energy in (((4, -5, 4), 1400), ((-5, 3, 2), 600), ((0, 0, -6), 500)):
        light = bpy.data.lights.new("L", "POINT")
        light.energy = energy
        obj = bpy.data.objects.new("L", light)
        obj.location = pos
        bpy.context.collection.objects.link(obj)


def clear_cameras():
    for cam in [o for o in bpy.data.objects if o.type == "CAMERA"]:
        bpy.data.objects.remove(cam, do_unlink=True)


def look_at(centre, distance, azimuth_deg, elevation_deg, lens=45):
    clear_cameras()
    az = math.radians(azimuth_deg)
    el = math.radians(elevation_deg)
    eye = Vector((
        centre.x + distance * math.cos(el) * math.sin(az),
        centre.y + distance * math.cos(el) * math.cos(az),
        centre.z + distance * math.sin(el),
    ))
    data = bpy.data.cameras.new("C")
    data.lens = lens
    cam = bpy.data.objects.new("C", data)
    cam.location = eye
    cam.rotation_euler = (centre - eye).to_track_quat("-Z", "Y").to_euler()
    bpy.context.collection.objects.link(cam)
    bpy.context.scene.camera = cam


def set_zone_alpha(zone_name, alpha):
    """Fade one cutaway zone, exactly as the app will at runtime.

    Every material the zone inherited from the bodywork under it has to move together, or half
    the window stays solid.
    """
    obj = bpy.data.objects.get(zone_name)
    if not obj:
        log(f"  {zone_name} MISSING")
        return
    n = 0
    for mat in obj.data.materials:
        if not mat or not mat.use_nodes:
            continue
        bsdf = mat.node_tree.nodes.get("Principled BSDF")
        if bsdf and "Alpha" in bsdf.inputs and not bsdf.inputs["Alpha"].is_linked:
            bsdf.inputs["Alpha"].default_value = alpha
            n += 1
    log(f"  {zone_name}: {n} material(s) -> alpha {alpha}")


def render(path):
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    log(f"  wrote {path.rsplit('/', 1)[-1]}")


def main():
    src, out_dir, alpha = args()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=src)
    setup_world()

    lo, hi = bounds(bpy.data.objects)
    centre = (lo + hi) / 2
    radius = max(hi[k] - lo[k] for k in range(3)) / 2
    log(f"bbox {tuple(round(v, 3) for v in lo)} .. {tuple(round(v, 3) for v in hi)}")

    # 1. what the driver normally sees: nothing faded anywhere.
    look_at(centre, radius * 2.4, 35, 14)
    render(f"{out_dir}/preview_1_normal.png")

    # 2/3. one window at a time, framed on the component it exposes.
    for zone, comp, az, el, name in (
        ("Zone_Motor", "Comp_Motor", 58, 12, "2_cutaway_motor"),
        ("Zone_Battery", "Comp_Battery", 62, -14, "3_cutaway_battery"),
    ):
        target = bpy.data.objects.get(comp)
        if target is None:
            log(f"  {comp} MISSING, skipping")
            continue
        clo, chi = bounds([target])
        set_zone_alpha(zone, alpha)
        look_at((clo + chi) / 2, radius * 1.5, az, el, lens=40)
        render(f"{out_dir}/preview_{name}.png")
        set_zone_alpha(zone, RESTING_ALPHA)

    # 4. both windows open from below, to confirm they do not interact oddly.
    for z in ZONES:
        set_zone_alpha(z, alpha)
    look_at(centre, radius * 2.4, 35, -38)
    render(f"{out_dir}/preview_4_cutaway_both.png")


if __name__ == "__main__":
    main()
