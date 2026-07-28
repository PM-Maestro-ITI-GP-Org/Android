# 05 · Diagnostics fragment (owner C)

Bound to **real** vehicle hardware. Interactive car with tappable component hotspots.

## Core interaction — hotspot tap → zoom
1. Idle: car shown with hotspot dots (battery, tires ×4, motor, brakes, doors).
   Dot color = semantic severity (green/amber/red).
2. **Tap a hotspot** → camera **zooms/animates** to that component (250 ms), the
   other hotspots fade, and a **live state card** slides in with that part's telemetry.
3. **Tap background / back** → zoom out to full car.
4. Only one component focused at a time.

## Hotspot behavior

| Hotspot | Zoom-in card shows | Source |
|---------|-------------------|--------|
| Battery | Charge %, cell temp, health %, cycles, charging state | `EV_BATTERY_LEVEL`, temp |
| Tire ×4 | PSI, temp, per-corner OK/low | `TIRE_PRESSURE` |
| Motor | Load, temp, RPM | motor props |
| Brakes | Pad wear %, fluid | brake props |
| Doors | Open/closed, locked | door/lock props |

## Buttons

| Control | Tap | Moving |
|---------|-----|--------|
| Hotspot dot | Zoom + open card (above) | allowed (read-only) |
| Alert row | Expand detail / jump to related component | allowed |
| Dismiss alert | Acknowledge (removes from list) | allowed |
| Health score ring | none (summary) | — |

## States & rules
- Live update: values refresh on `CarPropertyManager` change callbacks — no polling.
- Severity thresholds drive dot/card/alert color (green ≤ ok, amber = caution, red = critical).
- Sensor offline → card shows "No data", grey dot; never fake a value.
- Read-only fragment: it never writes to the vehicle.
