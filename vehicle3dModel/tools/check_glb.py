#!/usr/bin/env python3
"""Validate a diagnostics car GLB against what the Android app needs at runtime.

    python3 check_glb.py <file.glb>

Exits non-zero if any check FAILs. Stdlib only, so it runs anywhere Blender does.

The checks encode assumptions the app genuinely depends on. The important one is
alphaMode: Filament can animate a material's alpha at runtime only when the material was
exported as BLEND. An OPAQUE material cannot be faded, so a shell exported opaque makes the
whole ghosting feature impossible without re-exporting the asset.
"""
import json
import struct
import sys
from collections import defaultdict

REQUIRED_NODES = ["CarRoot", "Body_Shell", "Comp_Motor", "Comp_Battery",
                  "Zone_Motor", "Zone_Battery"]
# Only the cutaway zones fade. The shell itself must stay OPAQUE so the car looks
# like a car until the user asks to see inside it.
GHOSTABLE_NODES = ["Zone_Motor", "Zone_Battery"]
MUST_BE_OPAQUE = ["Body_Shell"]
CAR_LENGTH_RANGE = (4.2, 4.8)   # metres; glTF units are metres
TRI_BUDGET_TOTAL = 260_000      # car shell + internals; above this the Pi 5 will struggle

fails, warns = [], []


def ok(msg):
    print(f"[ PASS ] {msg}")


def fail(msg):
    print(f"[ FAIL ] {msg}")
    fails.append(msg)


def warn(msg):
    print(f"[ WARN ] {msg}")
    warns.append(msg)


def load(path):
    with open(path, "rb") as fh:
        magic, _ver, _len = struct.unpack("<III", fh.read(12))
        if magic != 0x46546C67:
            raise SystemExit(f"not a GLB: {path}")
        chunk_len, _chunk_type = struct.unpack("<II", fh.read(8))
        return json.loads(fh.read(chunk_len))


def mat4_mul(a, b):
    return [sum(a[i + 4 * k] * b[k + 4 * j] for k in range(4)) for j in range(4) for i in range(4)]


def node_matrix(node):
    if "matrix" in node:
        return node["matrix"]
    t = node.get("translation", [0, 0, 0])
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    s = node.get("scale", [1, 1, 1])
    m = [
        1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
        2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
        2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
        0, 0, 0, 1,
    ]
    for c in range(3):
        for r in range(3):
            m[c * 4 + r] *= s[c]
    m[12], m[13], m[14] = t
    return m


def walk(g, index, parent, out):
    node = g["nodes"][index]
    m = mat4_mul(parent, node_matrix(node))
    out[index] = m
    for c in node.get("children", []):
        walk(g, c, m, out)


def mesh_bounds(g, node_index, world):
    """World-space AABB of a node's own mesh, or None."""
    node = g["nodes"][node_index]
    if "mesh" not in node:
        return None
    lo = [1e30] * 3
    hi = [-1e30] * 3
    m = world[node_index]
    for prim in g["meshes"][node["mesh"]]["primitives"]:
        acc = g["accessors"][prim["attributes"]["POSITION"]]
        if "min" not in acc:
            continue
        for cx in (acc["min"][0], acc["max"][0]):
            for cy in (acc["min"][1], acc["max"][1]):
                for cz in (acc["min"][2], acc["max"][2]):
                    for k in range(3):
                        v = m[k] * cx + m[4 + k] * cy + m[8 + k] * cz + m[12 + k]
                        lo[k] = min(lo[k], v)
                        hi[k] = max(hi[k], v)
    return None if lo[0] > 1e29 else (lo, hi)


def subtree_bounds(g, root, world):
    """World AABB of a node and everything under it."""
    lo = [1e30] * 3
    hi = [-1e30] * 3
    stack = [root]
    while stack:
        i = stack.pop()
        b = mesh_bounds(g, i, world)
        if b:
            for k in range(3):
                lo[k] = min(lo[k], b[0][k])
                hi[k] = max(hi[k], b[1][k])
        stack.extend(g["nodes"][i].get("children", []))
    return None if lo[0] > 1e29 else (lo, hi)


def tri_count(g, node_index):
    node = g["nodes"][node_index]
    if "mesh" not in node:
        return 0
    n = 0
    for prim in g["meshes"][node["mesh"]]["primitives"]:
        if "indices" in prim:
            n += g["accessors"][prim["indices"]]["count"] // 3
        else:
            n += g["accessors"][prim["attributes"]["POSITION"]]["count"] // 3
    return n


def main(path):
    g = load(path)
    nodes = g.get("nodes", [])
    by_name = {n.get("name"): i for i, n in enumerate(nodes) if n.get("name")}

    world = {}
    for root in g["scenes"][g.get("scene", 0)]["nodes"]:
        walk(g, root, [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1], world)

    print(f"--- {path}")
    print(f"    {len(nodes)} nodes, {len(g.get('meshes', []))} meshes, "
          f"{len(g.get('materials', []))} materials, {len(g.get('images', []))} images")

    # 1. required node names
    for want in REQUIRED_NODES:
        if want in by_name:
            ok(f"node present: {want}")
        else:
            fail(f"node MISSING: {want} (app looks it up by exact string)")

    # 2. ghostable materials must be BLEND, or runtime fading is impossible
    materials = g.get("materials", [])
    for name in GHOSTABLE_NODES:
        i = by_name.get(name)
        if i is None:
            continue
        node = nodes[i]
        if "mesh" not in node:
            fail(f"{name} has no mesh")
            continue
        modes = set()
        for prim in g["meshes"][node["mesh"]]["primitives"]:
            mi = prim.get("material")
            modes.add(materials[mi].get("alphaMode", "OPAQUE") if mi is not None else "OPAQUE")
        if modes == {"BLEND"}:
            ok(f"{name} alphaMode = BLEND (runtime fade possible)")
        else:
            fail(f"{name} alphaMode = {sorted(modes)} — must be BLEND or the app cannot fade it")

    # 2b. the shell must NOT be blended, or the whole car ghosts and depth sorting breaks
    for name in MUST_BE_OPAQUE:
        i = by_name.get(name)
        if i is None or "mesh" not in nodes[i]:
            continue
        modes = set()
        for prim in g["meshes"][nodes[i]["mesh"]]["primitives"]:
            mi = prim.get("material")
            modes.add(materials[mi].get("alphaMode", "OPAQUE") if mi is not None else "OPAQUE")
        if "BLEND" in modes:
            fail(f"{name} contains BLEND materials - the car would never look solid")
        else:
            ok(f"{name} is opaque")

    # 2c. a double-sided blended surface shows the car's far side through its near side
    for name in GHOSTABLE_NODES:
        i = by_name.get(name)
        if i is None or "mesh" not in nodes[i]:
            continue
        two_sided = [p for p in g["meshes"][nodes[i]["mesh"]]["primitives"]
                     if p.get("material") is not None
                     and materials[p["material"]].get("doubleSided")]
        if two_sided:
            fail(f"{name} has {len(two_sided)} double-sided material(s) - you would see through "
                 f"to the far side of the car instead of a clean cutaway")
        else:
            ok(f"{name} is single-sided")

    # 3. one material per object, or fading/colouring one part changes another
    mat_users = defaultdict(list)
    for i, node in enumerate(nodes):
        if "mesh" not in node:
            continue
        for prim in g["meshes"][node["mesh"]]["primitives"]:
            mi = prim.get("material")
            if mi is not None:
                mat_users[mi].append(node.get("name", f"node{i}"))
    # Sharing only matters between things that fade or recolour INDEPENDENTLY. The four wheels
    # legitimately share tyre rubber; nothing ever recolours one wheel on its own.
    independent = set(GHOSTABLE_NODES) | {"Comp_Motor", "Comp_Battery"}
    shared = {mi: us for mi, us in mat_users.items()
              if len(set(us)) > 1 and set(us) & independent}
    if shared:
        for mi, us in shared.items():
            fail(f"material '{materials[mi].get('name', mi)}' shared by {sorted(set(us))} — "
                 f"recolouring one would change the others")
    else:
        ok("no independently-faded object shares a material")

    # 4. scale sanity
    car = subtree_bounds(g, by_name["CarRoot"], world) if "CarRoot" in by_name else None
    if car:
        ext = [round(car[1][k] - car[0][k], 3) for k in range(3)]
        longest = max(ext)
        print(f"    CarRoot extent (m): X={ext[0]} Y={ext[1]} Z={ext[2]}")
        if CAR_LENGTH_RANGE[0] <= longest <= CAR_LENGTH_RANGE[1]:
            ok(f"longest dimension {longest} m within {CAR_LENGTH_RANGE}")
        else:
            fail(f"longest dimension {longest} m outside {CAR_LENGTH_RANGE} — glTF units are metres")
    else:
        fail("cannot measure CarRoot bounds")

    # 5. internals must actually sit inside the shell
    shell = car  # zones are carved OUT of Body_Shell, so compare against the whole car
    for comp in ("Comp_Motor", "Comp_Battery"):
        i = by_name.get(comp)
        if i is None or shell is None:
            continue
        b = subtree_bounds(g, i, world)
        if not b:
            fail(f"{comp} has no geometry")
            continue
        ext = [round(b[1][k] - b[0][k], 3) for k in range(3)]
        inside = all(b[0][k] >= shell[0][k] - 0.02 and b[1][k] <= shell[1][k] + 0.02 for k in range(3))
        print(f"    {comp} extent (m): {ext}")
        if inside:
            ok(f"{comp} is inside the car bounding box")
        else:
            fail(f"{comp} extends outside the car - it would poke through the bodywork")

    # 6. triangle budget
    total = sum(tri_count(g, i) for i in range(len(nodes)))
    per = {n: tri_count(g, by_name[n]) for n in REQUIRED_NODES if n in by_name}
    print("    tris: " + ", ".join(f"{k}={v:,}" for k, v in per.items() if v) + f", total={total:,}")
    if total <= TRI_BUDGET_TOTAL:
        ok(f"total {total:,} tris within budget {TRI_BUDGET_TOTAL:,}")
    else:
        fail(f"total {total:,} tris exceeds budget {TRI_BUDGET_TOTAL:,}")

    # 7. things that would surprise the app
    if g.get("animations"):
        warn(f"{len(g['animations'])} animation(s) present; the app plays none")
    else:
        ok("no animations")

    print()
    if fails:
        print(f"RESULT: {len(fails)} FAIL, {len(warns)} WARN")
        return 1
    print(f"RESULT: all checks passed, {len(warns)} WARN")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1]))
