#!/usr/bin/env python3
"""Render preview PNGs of a prepared car GLB.

    blender --background --python preview.py -- <file.glb> <out_dir> [--alpha 0.2]

Three 512x512 renders: the car as exported, then the shell forced semi-transparent seen from a
front three-quarter and from below. The ghosted views are the point — they are the only way to
confirm the motor and battery are actually inside the shell and read through it, rather than
merely passing a bounding-box check.
"""
import math
import sys

import bpy
from mathutils import Vector

GHOST_NODES = ("Body_Shell", "Glass")
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


def scene_bbox():
    lo = Vector((1e30,) * 3)
    hi = Vector((-1e30,) * 3)
    for o in bpy.data.objects:
        if o.type != "MESH":
            continue
        for c in o.bound_box:
            w = o.matrix_world @ Vector(c)
            for k in range(3):
                lo[k] = min(lo[k], w[k])
                hi[k] = max(hi[k], w[k])
    return lo, hi


def setup_world():
    # Engine id differs across Blender versions: EEVEE Next is BLENDER_EEVEE_NEXT in 4.2-4.5
    # and back to plain BLENDER_EEVEE in 5.x. Pick whichever this build advertises.
    available = bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items.keys()
    for candidate in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        if candidate in available:
            bpy.context.scene.render.engine = candidate
            break
    log(f"  engine {bpy.context.scene.render.engine}")
    bpy.context.scene.render.resolution_x = RES
    bpy.context.scene.render.resolution_y = RES
    bpy.context.scene.render.film_transparent = False
    world = bpy.data.worlds.new("W")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.05, 0.06, 0.08, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 1.4
    bpy.context.scene.world = world

    for pos, energy in (((4, -5, 4), 1200), ((-5, 3, 2), 500), ((0, 0, -6), 400)):
        light = bpy.data.lights.new("L", "POINT")
        light.energy = energy
        obj = bpy.data.objects.new("L", light)
        obj.location = pos
        bpy.context.collection.objects.link(obj)


def place_camera(lo, hi, azimuth_deg, elevation_deg, distance_factor=2.4):
    centre = (lo + hi) / 2
    radius = max(hi[k] - lo[k] for k in range(3)) / 2
    d = radius * distance_factor
    az = math.radians(azimuth_deg)
    el = math.radians(elevation_deg)
    eye = Vector((
        centre.x + d * math.cos(el) * math.sin(az),
        centre.y + d * math.cos(el) * math.cos(az),
        centre.z + d * math.sin(el),
    ))
    cam_data = bpy.data.cameras.new("C")
    cam_data.lens = 45
    cam = bpy.data.objects.new("C", cam_data)
    cam.location = eye
    direction = centre - eye
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    bpy.context.collection.objects.link(cam)
    bpy.context.scene.camera = cam
    return cam


def set_ghost(alpha):
    """Force the shell translucent, exactly as the app will at runtime."""
    for name in GHOST_NODES:
        obj = bpy.data.objects.get(name)
        if not obj or not obj.data.materials:
            continue
        mat = obj.data.materials[0]
        bsdf = mat.node_tree.nodes.get("Principled BSDF")
        if bsdf and "Alpha" in bsdf.inputs:
            bsdf.inputs["Alpha"].default_value = alpha
        if hasattr(mat, "blend_method"):
            try:
                mat.blend_method = "BLEND"
            except TypeError:
                pass
        if hasattr(mat, "surface_render_method"):
            try:
                mat.surface_render_method = "BLENDED"
            except TypeError:
                pass
        log(f"  ghosted {name} alpha={alpha}")


def render(path):
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    log(f"  wrote {path}")


def main():
    src, out_dir, alpha = args()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=src)
    setup_world()
    lo, hi = scene_bbox()
    log(f"bbox {tuple(round(v,3) for v in lo)} .. {tuple(round(v,3) for v in hi)}")

    for cam in [o for o in bpy.data.objects if o.type == "CAMERA"]:
        bpy.data.objects.remove(cam, do_unlink=True)
    place_camera(lo, hi, 35, 14)
    render(f"{out_dir}/preview_1_solid.png")

    set_ghost(alpha)
    render(f"{out_dir}/preview_2_ghost_front.png")

    for cam in [o for o in bpy.data.objects if o.type == "CAMERA"]:
        bpy.data.objects.remove(cam, do_unlink=True)
    place_camera(lo, hi, 35, -38)
    render(f"{out_dir}/preview_3_ghost_below.png")

    # Dead side-on: the only view where "is the motor at the rear axle" is answerable at a
    # glance. Three-quarter views hide longitudinal error behind perspective.
    for cam in [o for o in bpy.data.objects if o.type == "CAMERA"]:
        bpy.data.objects.remove(cam, do_unlink=True)
    place_camera(lo, hi, 90, 2, distance_factor=2.1)
    render(f"{out_dir}/preview_4_ghost_side.png")


if __name__ == "__main__":
    main()
