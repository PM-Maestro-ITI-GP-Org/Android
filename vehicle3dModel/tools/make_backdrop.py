#!/usr/bin/env python3
"""Generate the diagnostics stage backdrop textures.

    python3 make_backdrop.py ../../MotorGuardApp/app/src/main/assets

Writes stage_floor_night.png and stage_floor_day.png: a perforated dot grid with a soft radial
glow under where the car stands, in the two theme flavours.

The grid is a FLOOR, laid flat in the scene, not a backdrop standing behind the car. That is the
whole reason it convinces: perspective does the work, converging the rows towards the horizon, so
the car reads as standing on something rather than being pasted in front of a dotted wall. Nothing
here paints a horizon or a wall-to-floor blend any more — the plane's own angle to the camera is
the horizon.

Why a texture and not a Compose background: the car stage is an opaque SurfaceView, which punches
through the window wherever it is drawn, so anything Compose paints *behind* it is erased (see
Car3dRenderer's class KDoc). The backdrop therefore has to be part of the 3D scene, and the
cheapest way to put an image there is a textured quad — see Car3dRenderer's StageBackdrop.

Regenerate rather than edit: every constant that shapes the image is in this file.
"""
import math
import struct
import sys
import zlib

W, H = 2048, 2048

# --- Night. Sampled to sit a shade darker than the stage colour so the car keeps its contrast.
NIGHT = dict(
    base=(11, 15, 22),
    dot=(41, 52, 70),
    glow=(38, 84, 148),
    glow_strength=0.55,
    contact=(2, 4, 7),
    contact_strength=0.85,
    floor=(17, 23, 33),
    edge=(6, 8, 12),
)

# --- Day. Same construction, inverted weight: a light card with grey perforation.
#
# Authored a good deal darker than it is meant to look. The backdrop is drawn by Filament, whose
# tone mapping compresses the top of the range hard: the first pass at this palette (base 233,
# dots 203) arrived on screen as flat white with the dot grid gone. These values land where those
# were intended to. Judge them on the device, never in an image viewer.
DAY = dict(
    base=(178, 184, 194),
    dot=(132, 141, 156),
    glow=(96, 140, 196),
    glow_strength=0.45,
    contact=(120, 128, 142),
    contact_strength=0.55,
    floor=(160, 167, 179),
    edge=(199, 204, 212),
)

# Spacing is in texture pixels, but what matters is what it becomes in the world: the plane is
# about ten car-lengths across, so this lands the dots roughly half a metre apart on the floor.
DOT_SPACING = 18      # px between dot centres
DOT_RADIUS = 1.9      # px
# Centred: both the glow and the contact pool sit directly under the car, which is the middle of
# the plane, and stay there however the camera orbits because the plane is fixed in the world.
GLOW_CENTER = (0.5, 0.5)

# The soft pool of shade the car sits in.
#
# Painted rather than cast. A real shadow needs the shadow map turned on, which costs a pass over
# the whole scene every frame on a Pi, and the sun angle then decides where it lands — pointing
# away from the camera as often as not. This is always under the car, always the same shape, and
# free. The car does not move relative to the floor, so nothing is lost by baking it.
CONTACT_RADIUS = 0.20   # fraction of the image width
CONTACT_SQUASH = 2.1    # >1 makes it an ellipse along the car, not a circle
GLOW_RADIUS = 0.46    # fraction of the image width
# The plane is finite, so its edge has to disappear rather than end. A radial fade to the theme's
# own edge colour does that: by the time the quad runs out, it has already become the stage.
FADE_START = 0.14     # fraction of the half-diagonal where the fade begins
FADE_POWER = 1.25     # >1 keeps the middle clean and pushes the falloff outwards


def build(theme):
    base = theme["base"]
    dot = theme["dot"]
    glow = theme["glow"]
    floor = theme["floor"]
    gx, gy = GLOW_CENTER[0] * W, GLOW_CENTER[1] * H
    grad = GLOW_RADIUS * W
    rows = []
    for y in range(H):
        row = bytearray()
        row.append(0)  # PNG filter type: none
        for x in range(W):
            # One flat surface colour. The old wall-to-floor gradient was painted because the
            # quad faced the camera and had to imply a horizon; a real floor gets its shading
            # from the light and its perspective from the camera.
            r, g, b = float(floor[0]), float(floor[1]), float(floor[2])

            # Dot grid, anti-aliased by distance to the nearest lattice point.
            dx = abs((x % DOT_SPACING) - DOT_SPACING / 2)
            dy = abs((y % DOT_SPACING) - DOT_SPACING / 2)
            d = math.hypot(dx, dy)
            k = max(0.0, min(1.0, (DOT_RADIUS + 0.7 - d) / 1.4))
            if k > 0:
                r += (dot[0] - r) * k
                g += (dot[1] - g) * k
                b += (dot[2] - b) * k

            # Soft contact pool, before the glow so the glow lifts its edges rather than being
            # swallowed by it.
            cd = math.hypot((x - gx) / (CONTACT_RADIUS * W),
                            (y - gy) * CONTACT_SQUASH / (CONTACT_RADIUS * W))
            if cd < 1.0:
                k = (1.0 - cd) ** 2 * theme["contact_strength"]
                contact = theme["contact"]
                r += (contact[0] - r) * k
                g += (contact[1] - g) * k
                b += (contact[2] - b) * k

            # Radial glow behind the car, quadratic falloff.
            gd = math.hypot(x - gx, y - gy) / grad
            if gd < 1.0:
                k = (1.0 - gd) ** 2 * theme["glow_strength"]
                r += (glow[0] - r) * k
                g += (glow[1] - g) * k
                b += (glow[2] - b) * k

            # Radial fade towards the theme's own edge colour, so the plane dissolves into the
            # stage instead of ending in a visible seam where its geometry stops. Darkening would
            # be wrong in day mode: a light card with black corners reads as a stain, not a fade.
            vx = (x / W - 0.5) * 2
            vy = (y / H - 0.5) * 2
            d = math.sqrt(vx * vx + vy * vy) / math.sqrt(2.0)
            v = 0.0 if d < FADE_START else min(1.0, ((d - FADE_START) / (1.0 - FADE_START)) ** FADE_POWER)
            edge = theme["edge"]
            r += (edge[0] - r) * v
            g += (edge[1] - g) * v
            b += (edge[2] - b) * v
            row += bytes((int(r), int(g), int(b)))
        rows.append(bytes(row))
    return b"".join(rows)


def write_png(path, raw):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)  # 8-bit truecolour
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    print(f"wrote {path} ({len(png) // 1024} KB)")


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    write_png(f"{out}/stage_floor_night.png", build(NIGHT))
    write_png(f"{out}/stage_floor_day.png", build(DAY))
