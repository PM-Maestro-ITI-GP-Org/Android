#!/usr/bin/env python3
"""Generate the diagnostics stage backdrop textures.

    python3 make_backdrop.py ../../MotorGuardApp/app/src/main/assets

Writes stage_backdrop_night.png and stage_backdrop_day.png: a perforated dot grid with a soft
radial glow behind where the car stands, in the two theme flavours.

Why a texture and not a Compose background: the car stage is an opaque SurfaceView, which punches
through the window wherever it is drawn, so anything Compose paints *behind* it is erased (see
Car3dRenderer's class KDoc). The backdrop therefore has to be part of the 3D scene, and the
cheapest way to put an image there is a billboarded quad — see Car3dRenderer.StageBackdrop.

Regenerate rather than edit: every constant that shapes the image is in this file.
"""
import math
import struct
import sys
import zlib

W, H = 1024, 1024

# --- Night. Sampled to sit a shade darker than the stage colour so the car keeps its contrast.
NIGHT = dict(
    base=(11, 15, 22),
    dot=(41, 52, 70),
    glow=(38, 84, 148),
    glow_strength=0.55,
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
    floor=(160, 167, 179),
    edge=(199, 204, 212),
)

DOT_SPACING = 26      # px between dot centres
DOT_RADIUS = 2.1      # px
GLOW_CENTER = (0.5, 0.56)
GLOW_RADIUS = 0.46    # fraction of the image width
HORIZON = 0.72        # fraction of the height where the backdrop turns into "floor"
VIGNETTE = 0.55       # how hard the corners fall off


def build(theme):
    base = theme["base"]
    dot = theme["dot"]
    glow = theme["glow"]
    floor = theme["floor"]
    gx, gy = GLOW_CENTER[0] * W, GLOW_CENTER[1] * H
    grad = GLOW_RADIUS * W
    rows = []
    for y in range(H):
        # Straight vertical blend from wall to floor, so the car has something to stand on.
        t = 0.0 if y < HORIZON * H else min(1.0, (y - HORIZON * H) / (H * (1 - HORIZON)))
        row = bytearray()
        row.append(0)  # PNG filter type: none
        for x in range(W):
            r = base[0] + (floor[0] - base[0]) * t
            g = base[1] + (floor[1] - base[1]) * t
            b = base[2] + (floor[2] - base[2]) * t

            # Dot grid, anti-aliased by distance to the nearest lattice point.
            dx = abs((x % DOT_SPACING) - DOT_SPACING / 2)
            dy = abs((y % DOT_SPACING) - DOT_SPACING / 2)
            d = math.hypot(dx, dy)
            k = max(0.0, min(1.0, (DOT_RADIUS + 0.7 - d) / 1.4))
            if k > 0:
                r += (dot[0] - r) * k
                g += (dot[1] - g) * k
                b += (dot[2] - b) * k

            # Radial glow behind the car, quadratic falloff.
            gd = math.hypot(x - gx, y - gy) / grad
            if gd < 1.0:
                k = (1.0 - gd) ** 2 * theme["glow_strength"]
                r += (glow[0] - r) * k
                g += (glow[1] - g) * k
                b += (glow[2] - b) * k

            # Vignette towards the theme's own edge colour, so the quad dissolves into the
            # stage instead of ending in a visible seam. Darkening would be wrong in day mode:
            # a light card with black corners reads as a stain, not a fade.
            vx = (x / W - 0.5) * 2
            vy = (y / H - 0.5) * 2
            v = VIGNETTE * min(1.0, (vx * vx + vy * vy) * 0.6)
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
    write_png(f"{out}/stage_backdrop_night.png", build(NIGHT))
    write_png(f"{out}/stage_backdrop_day.png", build(DAY))
