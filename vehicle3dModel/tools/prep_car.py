#!/usr/bin/env python3
"""Prepare a car GLB for the Android diagnostics app.

    blender --background --python prep_car.py -- <in.glb> <out.glb> \
        [--motor <motor.glb>] [--battery <battery.glb>] [--tri-budget 6000]

Produces a GLB whose shell the app can fade at runtime, with motor and battery components
inside it that the app can recolour to signal severity.

Idempotent by construction: the scene is wiped and every input re-imported on each run, so
running it repeatedly from the original input always yields the same output. Never edit the
output by hand; change a constant here and re-run.

The single most fragile thing here is alphaMode. Filament can only animate a material's alpha
if the material was exported BLEND, so the shell's transparency has to be baked in at export
time. The property that controls this moved in Blender 4.2 (EEVEE Next), so both the old and
new spellings are set and the result is verified by check_glb.py rather than assumed.
"""
import math
import sys

import bpy
from mathutils import Vector

# ---------------------------------------------------------------- placement constants
# All sizes are fractions of the car's own bounding box, so they survive a model swap.

MOTOR_DIAMETER_FRAC = 0.16     # of car length
MOTOR_LENGTH_FRAC = 0.12       # of car length, along the lateral axis
MOTOR_HEIGHT_FRAC = 0.25       # above the floor plane, as a fraction of car height
MOTOR_LON_FRAC = -0.34         # along length from centre; negative is rearward (rear axle)

BATTERY_LENGTH_FRAC = 0.55     # of car length
BATTERY_WIDTH_FRAC = 0.60      # of car width
BATTERY_HEIGHT_FRAC = 0.15     # of car height; a real pack with brackets is ~0.16 m
BATTERY_FLOOR_CLEARANCE = 0.02 # of car height, above the floor plane
BATTERY_LON_FRAC = -0.02       # slightly rearward of centre, as real skateboard packs sit

CAR_LENGTH_RANGE = (4.2, 4.8)  # metres
DEFAULT_COMPONENT_TRIS = 6000  # per imported component after decimation

# Classification of the source car's parts. Matched against object AND material names, so a
# renamed mesh with an intact material still lands in the right group.
GLASS_HINTS = ("glass", "porgls", "window", "windshield", "windscreen")
WHEEL_HINTS = ("tire", "tyre", "rim", "wheel")


def log(msg):
    print(f"[prep] {msg}", flush=True)


def argv_after_dashes():
    return sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []


def parse_args():
    a = argv_after_dashes()
    if len(a) < 2:
        raise SystemExit("usage: ... -- <in.glb> <out.glb> [--motor f] [--battery f] "
                         "[--tri-budget n]")
    opts = {"in": a[0], "out": a[1], "motor": None, "battery": None,
            "tris": DEFAULT_COMPONENT_TRIS}
    rest = a[2:]
    for i, tok in enumerate(rest):
        if tok == "--motor" and i + 1 < len(rest):
            opts["motor"] = rest[i + 1]
        elif tok == "--battery" and i + 1 < len(rest):
            opts["battery"] = rest[i + 1]
        elif tok == "--tri-budget" and i + 1 < len(rest):
            opts["tris"] = int(rest[i + 1])
    return opts


# ---------------------------------------------------------------- scene helpers

def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def import_glb(path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=path)
    return [o for o in bpy.data.objects if o not in before]


def mesh_objects(objs):
    return [o for o in objs if o.type == "MESH"]


def tri_count(obj):
    return sum(len(p.vertices) - 2 for p in obj.data.polygons)


def world_bbox(objs):
    lo = Vector((1e30,) * 3)
    hi = Vector((-1e30,) * 3)
    for o in objs:
        for corner in o.bound_box:
            w = o.matrix_world @ Vector(corner)
            for k in range(3):
                lo[k] = min(lo[k], w[k])
                hi[k] = max(hi[k], w[k])
    return lo, hi


def deselect_all():
    for o in bpy.data.objects:
        o.select_set(False)
    bpy.context.view_layer.objects.active = None


def join(objs, name):
    """Merge objects into one named object. Returns None if the list is empty."""
    objs = [o for o in objs if o.name in bpy.data.objects]
    if not objs:
        return None
    deselect_all()
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    if len(objs) > 1:
        bpy.ops.object.join()
    result = bpy.context.view_layer.objects.active
    result.name = name
    result.data.name = name + "_mesh"
    return flatten(result)


def flatten(obj):
    """Detach from any imported parent, keeping world position, then bake transforms in.

    Load-bearing. Both the car and the component GLBs carry a transform matrix on their root
    nodes (Sketchfab exports do this routinely). Re-parenting a child to CarRoot later silently
    drops that ancestor matrix, so the object measures correctly in Blender and then exports at
    raw local scale — which is exactly how a 4.5 m car exported as 580 m.
    """
    deselect_all()
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    if obj.parent:
        bpy.ops.object.parent_clear(type="CLEAR_KEEP_TRANSFORM")
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return obj


def drop_slivers(objs, max_aspect=8.0):
    """Discard cables, curves and mounting rails from a component model.

    Judged by aspect ratio rather than size: a motor housing is chunky, whereas the loom and
    conduit that ship with a workshop model are long and thin. Keeping them would let a single
    447-unit cable define the motor's bounding box and shrink the actual motor to a splinter
    once it is scaled to fit inside the car.
    """
    kept, dropped = [], []
    for o in objs:
        d = sorted([o.dimensions.x, o.dimensions.y, o.dimensions.z], reverse=True)
        aspect = d[0] / max(1e-6, d[1])
        (dropped if aspect > max_aspect else kept).append((o, aspect))
    for o, a in dropped:
        log(f"    dropped sliver {o.name[:28]:28s} aspect {a:.1f}:1")
        bpy.data.objects.remove(o, do_unlink=True)
    return [o for o, _ in kept]


def apply_transforms(obj):
    deselect_all()
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def origin_to_geometry(obj):
    """The app uses each object's origin as its camera look-at anchor."""
    deselect_all()
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.origin_set(type="ORIGIN_GEOMETRY", center="BOUNDS")


def decimate_to(obj, target_tris):
    current = tri_count(obj)
    if current <= target_tris:
        return current
    deselect_all()
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    mod = obj.modifiers.new("decimate", "DECIMATE")
    mod.decimate_type = "COLLAPSE"
    mod.ratio = max(0.01, target_tris / current)
    bpy.ops.object.modifier_apply(modifier=mod.name)
    return tri_count(obj)


# ---------------------------------------------------------------- materials

def make_material(name, base_rgba, metallic, roughness, emission_strength=0.0, blend=False):
    """One unique material per object; sharing would make fading one part fade another."""
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = base_rgba
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if "Alpha" in bsdf.inputs:
        bsdf.inputs["Alpha"].default_value = base_rgba[3]
    if emission_strength > 0:
        # Emissive so the internals stay legible through a semi-transparent shell.
        for key in ("Emission Color", "Emission"):
            if key in bsdf.inputs:
                bsdf.inputs[key].default_value = base_rgba
                break
        if "Emission Strength" in bsdf.inputs:
            bsdf.inputs["Emission Strength"].default_value = emission_strength

    if blend:
        # The property moved in Blender 4.2 (EEVEE Next). Set whichever spelling exists; the
        # glTF exporter reads one of them to decide alphaMode, and check_glb.py is what
        # confirms the export actually came out BLEND.
        applied = []
        if hasattr(mat, "blend_method"):
            try:
                mat.blend_method = "BLEND"
                applied.append("blend_method=BLEND")
            except TypeError:
                pass
        if hasattr(mat, "surface_render_method"):
            try:
                mat.surface_render_method = "BLENDED"
                applied.append("surface_render_method=BLENDED")
            except TypeError:
                pass
        if hasattr(mat, "show_transparent_back"):
            mat.show_transparent_back = False
        log(f"  material {name}: {', '.join(applied) or 'NO blend property found'}")
    return mat


def assign_only(obj, mat):
    obj.data.materials.clear()
    obj.data.materials.append(mat)


# ---------------------------------------------------------------- axis derivation

def derive_axes(lo, hi):
    """Return (length_axis, width_axis, height_axis) indices from the car's own bbox.

    A car is always longer than it is wide, and always shortest in height, so argmax/argmin of
    the extents identifies the axes without assuming which way the exporter oriented things.
    """
    ext = [hi[k] - lo[k] for k in range(3)]
    length = max(range(3), key=lambda k: ext[k])
    height = min(range(3), key=lambda k: ext[k])
    width = 3 - length - height
    return length, width, height, ext


# ---------------------------------------------------------------- component building

def align_axes(obj, length_axis, width_axis, height_axis):
    """Rotate in 90-degree steps until the object's longest/mid/shortest sit on the car's
    length/width/height.

    Without this the fit-inside scale picks the wrong constraint: a battery pack lying with its
    105-unit thin axis across the car's WIDTH gets squeezed by the height target and comes out a
    third of its proper length. Only three swaps are ever needed, each a 90-degree turn about one
    axis, so this is exact rather than a search.
    """
    want = [length_axis, width_axis, height_axis]
    for _ in range(3):
        lo, hi = world_bbox([obj])
        order = sorted(range(3), key=lambda k: -(hi[k] - lo[k]))
        if order == want:
            return
        # Find the first slot that is wrong and swap the offending pair.
        for slot in range(3):
            if order[slot] != want[slot]:
                a, b = order[slot], want[slot]
                spin = 3 - a - b  # the axis perpendicular to both is the one to turn about
                axis = [0.0, 0.0, 0.0]
                axis[spin] = 1.0
                obj.rotation_mode = "AXIS_ANGLE"
                obj.rotation_axis_angle = (math.pi / 2, *axis)
                apply_transforms(obj)
                break


def place_component(obj, ref_lo, ref_hi, axes, target_ext, lon_frac, height_frac):
    """Scale a component to fit its target box and drop it into the car's frame.

    [ref_lo]/[ref_hi] are the BODY SHELL's bounds, not the whole car's. The wheels hang below the
    floor pan, so measuring from the car's overall bbox puts the "floor plane" underneath the
    shell and pushes the battery out through the bottom.
    """
    length_axis, width_axis, height_axis, ext = axes
    apply_transforms(obj)
    origin_to_geometry(obj)
    align_axes(obj, length_axis, width_axis, height_axis)

    clo, chi = world_bbox([obj])
    cext = [max(1e-6, chi[k] - clo[k]) for k in range(3)]

    # Uniform, fitted INSIDE the target box rather than stretched to fill it: real proportions are
    # what keep it reading as a motor or a pack, and fitting inside is what guarantees it cannot
    # poke through the bodywork.
    s = min(target_ext[k] / cext[k] for k in range(3))
    obj.scale = (s, s, s)
    apply_transforms(obj)
    origin_to_geometry(obj)

    centre = [(ref_lo[k] + ref_hi[k]) / 2 for k in range(3)]
    ref_ext = [ref_hi[k] - ref_lo[k] for k in range(3)]
    pos = list(centre)
    pos[length_axis] = centre[length_axis] + lon_frac * ref_ext[length_axis]
    pos[height_axis] = ref_lo[height_axis] + height_frac * ref_ext[height_axis]
    obj.location = Vector(pos)
    apply_transforms(obj)
    origin_to_geometry(obj)
    return obj


def build_procedural_motor(lo, hi, axes):
    length_axis, width_axis, height_axis, ext = axes
    car_len = ext[length_axis]
    bpy.ops.mesh.primitive_cylinder_add(vertices=24, radius=car_len * MOTOR_DIAMETER_FRAC / 2,
                                        depth=car_len * MOTOR_LENGTH_FRAC)
    body = bpy.context.active_object
    bpy.ops.mesh.primitive_cylinder_add(vertices=12, radius=car_len * MOTOR_DIAMETER_FRAC / 6,
                                        depth=car_len * MOTOR_LENGTH_FRAC * 1.5)
    shaft = bpy.context.active_object
    motor = join([body, shaft], "Comp_Motor")
    return motor


def build_procedural_battery(lo, hi, axes):
    length_axis, width_axis, height_axis, ext = axes
    bpy.ops.mesh.primitive_cube_add(size=1)
    box = bpy.context.active_object
    box.scale = (1, 1, 1)
    apply_transforms(box)
    return join([box], "Comp_Battery")


# ---------------------------------------------------------------- main

def main():
    opts = parse_args()
    log(f"in  = {opts['in']}")
    log(f"out = {opts['out']}")

    reset_scene()

    # ---- 1. inventory
    car_objs = mesh_objects(import_glb(opts["in"]))
    if not car_objs:
        raise SystemExit("ABORT: no mesh objects imported from the car GLB")
    log(f"imported {len(car_objs)} car mesh objects")
    total_in = 0
    for o in sorted(car_objs, key=lambda x: -tri_count(x))[:12]:
        mats = ",".join(m.name for m in o.data.materials if m) or "-"
        d = o.dimensions
        total_in += 0
        log(f"  {o.name[:38]:38s} {tri_count(o):7d}t  [{mats[:28]}]  "
            f"{d.x:.2f}x{d.y:.2f}x{d.z:.2f}")
    total_in = sum(tri_count(o) for o in car_objs)
    log(f"  ... {len(car_objs)} objects, {total_in:,} tris total")
    lo, hi = world_bbox(car_objs)
    log(f"  bbox lo={tuple(round(v,3) for v in lo)} hi={tuple(round(v,3) for v in hi)}")

    # ---- 2. classify and merge
    def kind(o):
        hay = (o.name + " " + " ".join(m.name for m in o.data.materials if m)).lower()
        if any(h in hay for h in WHEEL_HINTS):
            return "wheel"
        if any(h in hay for h in GLASS_HINTS):
            return "glass"
        return "body"

    groups = {"body": [], "glass": [], "wheel": []}
    for o in car_objs:
        groups[kind(o)].append(o)
    log(f"classified: body={len(groups['body'])} glass={len(groups['glass'])} "
        f"wheel={len(groups['wheel'])}")

    axes = derive_axes(lo, hi)
    length_axis, width_axis, height_axis, ext = axes
    centre = [(lo[k] + hi[k]) / 2 for k in range(3)]

    # Which way the car faces. Taken from the brake meshes, which are the only parts in this
    # model named front/rear, and read BEFORE they are merged into Body_Shell and lose their
    # names. Without it "put the motor at the rear axle" is a coin flip on the model's
    # orientation, and the motor lands under the bonnet.
    def group_centre(pred):
        sel = [o for o in car_objs if pred(o.name.lower())]
        if not sel:
            return None
        glo, ghi = world_bbox(sel)
        return (glo[length_axis] + ghi[length_axis]) / 2

    f = group_centre(lambda n: "brakes_front" in n)
    r = group_centre(lambda n: "brakes_rear" in n)
    if f is not None and r is not None:
        front_sign = 1.0 if f > r else -1.0
        log(f"front direction: {'+' if front_sign > 0 else '-'} along axis {length_axis} "
            f"(front brakes {f:.2f}, rear {r:.2f})")
    else:
        front_sign = 1.0
        log("front direction UNRESOLVED (no brakes_front/rear meshes); assuming +length")

    # Wheels split into corners by position; the app never relies on which is which here, but a
    # human reading the tree does, and the names are part of the agreed output structure.
    wheels = {}
    if groups["wheel"]:
        for o in groups["wheel"]:
            wlo, whi = world_bbox([o])
            c = [(wlo[k] + whi[k]) / 2 for k in range(3)]
            front = c[length_axis] >= centre[length_axis]
            right = c[width_axis] >= centre[width_axis]
            key = f"Wheel_{'F' if front else 'R'}{'R' if right else 'L'}"
            wheels.setdefault(key, []).append(o)
        for key, objs in list(wheels.items()):
            wheels[key] = join(objs, key)
        log("wheels: " + ", ".join(f"{k}={tri_count(v):,}t" for k, v in wheels.items()))

    glass = join(groups["glass"], "Glass") if groups["glass"] else None
    shell = join(groups["body"], "Body_Shell")
    if shell is None:
        raise SystemExit("ABORT: no bodywork mesh identified")
    log(f"Body_Shell {tri_count(shell):,}t   Glass "
        f"{tri_count(glass):,}t" if glass else f"Body_Shell {tri_count(shell):,}t   Glass -")

    # ---- 3/4. internals, measured against the SHELL rather than the whole car
    slo, shi = world_bbox([shell])
    sext = [shi[k] - slo[k] for k in range(3)]
    log(f"  shell bbox ext={tuple(round(v,3) for v in sext)}")

    motor_target = [0.0, 0.0, 0.0]
    motor_target[length_axis] = sext[length_axis] * MOTOR_DIAMETER_FRAC
    motor_target[width_axis] = sext[length_axis] * MOTOR_LENGTH_FRAC
    motor_target[height_axis] = sext[length_axis] * MOTOR_DIAMETER_FRAC

    if opts["motor"]:
        objs = drop_slivers(mesh_objects(import_glb(opts["motor"])))
        motor = join(objs, "Comp_Motor")
        log(f"Comp_Motor imported {tri_count(motor):,}t -> "
            f"{decimate_to(motor, opts['tris']):,}t")
    else:
        motor = build_procedural_motor(lo, hi, axes)
        log(f"Comp_Motor procedural {tri_count(motor):,}t")
    place_component(motor, slo, shi, axes, motor_target,
                    MOTOR_LON_FRAC * front_sign, MOTOR_HEIGHT_FRAC)

    batt_target = [0.0, 0.0, 0.0]
    batt_target[length_axis] = sext[length_axis] * BATTERY_LENGTH_FRAC
    batt_target[width_axis] = sext[width_axis] * BATTERY_WIDTH_FRAC
    batt_target[height_axis] = sext[height_axis] * BATTERY_HEIGHT_FRAC

    if opts["battery"]:
        objs = drop_slivers(mesh_objects(import_glb(opts["battery"])))
        battery = join(objs, "Comp_Battery")
        log(f"Comp_Battery imported {tri_count(battery):,}t -> "
            f"{decimate_to(battery, opts['tris']):,}t")
    else:
        battery = build_procedural_battery(lo, hi, axes)
        log(f"Comp_Battery procedural {tri_count(battery):,}t")
    place_component(battery, slo, shi, axes, batt_target,
                    BATTERY_LON_FRAC * front_sign,
                    BATTERY_FLOOR_CLEARANCE + BATTERY_HEIGHT_FRAC / 2)

    # ---- 5. materials, one per object
    assign_only(shell, make_material("MatBodyShell", (0.42, 0.44, 0.47, 0.99),
                                     metallic=0.7, roughness=0.25, blend=True))
    assign_only(motor, make_material("MatMotor", (0.62, 0.66, 0.72, 1.0),
                                     metallic=0.5, roughness=0.4, emission_strength=0.8))
    assign_only(battery, make_material("MatBattery", (0.60, 0.64, 0.70, 1.0),
                                       metallic=0.4, roughness=0.5, emission_strength=0.8))
    if glass:
        assign_only(glass, make_material("MatGlass", (0.30, 0.34, 0.38, 0.35),
                                         metallic=0.0, roughness=0.05, blend=True))
    for key, obj in wheels.items():
        assign_only(obj, make_material(f"Mat{key}", (0.10, 0.10, 0.11, 1.0),
                                       metallic=0.3, roughness=0.6))

    # ---- 6. transforms and scale
    parts = [p for p in [shell, glass, motor, battery, *wheels.values()] if p]
    for p in parts:
        origin_to_geometry(p)
        apply_transforms(p)

    lo2, hi2 = world_bbox(parts)
    longest = max(hi2[k] - lo2[k] for k in range(3))
    if not (CAR_LENGTH_RANGE[0] <= longest <= CAR_LENGTH_RANGE[1]):
        target = sum(CAR_LENGTH_RANGE) / 2
        factor = target / longest
        log(f"scaling x{factor:.4f} to bring longest {longest:.3f} m into {CAR_LENGTH_RANGE}")
        for p in parts:
            p.scale = (factor, factor, factor)
            apply_transforms(p)
            origin_to_geometry(p)
        lo2, hi2 = world_bbox(parts)
        longest = max(hi2[k] - lo2[k] for k in range(3))
    log(f"final longest dimension {longest:.3f} m")

    # Drop everything that is not one of our parts. The glTF importers leave a hierarchy of
    # empties behind, and they export as real nodes: without this the file carries ~280 stray
    # nodes still named geo_tire_*, geo_doors_* and so on, which is actively misleading to
    # anything that inspects the model by node name.
    keep = {p.name for p in parts}
    removed = 0
    for o in list(bpy.data.objects):
        if o.name not in keep:
            bpy.data.objects.remove(o, do_unlink=True)
            removed += 1
    log(f"removed {removed} leftover empty/helper objects")

    # ---- parent under CarRoot
    root = bpy.data.objects.new("CarRoot", None)
    bpy.context.collection.objects.link(root)
    for p in parts:
        p.parent = root
        p.matrix_parent_inverse = root.matrix_world.inverted()

    for p in parts:
        log(f"  node {p.name:14s} {tri_count(p):7,}t  "
            f"mat={p.data.materials[0].name if p.data.materials else '-'}")

    # ---- 7. export
    bpy.ops.export_scene.gltf(
        filepath=opts["out"],
        export_format="GLB",
        export_yup=True,
        export_apply=True,
        export_materials="EXPORT",
        export_draco_mesh_compression_enable=False,
        export_animations=False,
        export_extras=True,
        use_selection=False,
    )
    log(f"exported {opts['out']}")


if __name__ == "__main__":
    main()
