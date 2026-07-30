# 08 · Navigation fragment

Destination search → route preview → turn-by-turn guidance, on a full-bleed map card.
No Google Maps: this is an AOSP image with no Play Services, so the Maps SDK could not
initialize even if we wanted it. Everything below is open source.

## Stack

| Concern | Implementation | Why | Swap point |
|---------|----------------|-----|-----------|
| Map render | **MapLibre Native** (BSD-2) | GL vector tiles; style is JSON, so the map takes our palette exactly | `MapBackend` |
| Tiles | **OpenFreeMap** planet (OpenMapTiles schema) | No key, no quota, no registration; self-hostable | `NavConfig.tileJsonUrl` |
| Routing | **Valhalla** via FOSSGIS | Richest maneuver output (typed turns + street names + shape indices) | `RoutingService` |
| Search | **Photon** (komoot) | Built for search-as-you-type; Nominatim's policy forbids autocomplete | `GeocodingService` |
| Position | **Simulator** | Demoable at a desk with no GPS and no sky | `LocationSource` |

The three public instances are fair-use. Fine for development and the defence; point
`NavConfig` at self-hosted Valhalla + Photon + tile server before this goes in a car.

## Fallbacks — both automatic

1. **No internet** → the Canvas renderer (`CanvasMapSurface`): an abstract street grid with
   the *real* route drawn on it in true Web Mercator. Checked up front via
   `ConnectivityManager`, because a tile fetch that never completes is silent — MapLibre
   would render an empty world rather than report an error.
2. **MapLibre will not start** (the Pi's `v3d` GL driver throws `UnsatisfiedLinkError` from
   the native loader) → same Canvas renderer, for the rest of the session.

Neither is defensive padding. A nav screen that draws a schematic beats one that draws a
black rectangle on demo day.

## Layout

One map card (28 dp radius, 22 dp inset) with glass overlays, matching the nav surface in
`MeowScreen.dc.html`:

```
┌──────────────────────────────────────────────────┐
│ [maneuver card]                    [speed puck]  │
│ [then… chip]                       [re-centre]   │
│                                                  │
│                     ▲  ← car at 70% height       │
│                                                  │
│ © OpenStreetMap     [arrival │ ETA ···  🔊  ✕ ]  │
└──────────────────────────────────────────────────┘
```

## Phases

`NavPhase` is a sealed hierarchy, so "guiding with no route" is unrepresentable.

| Phase | Map camera | Overlay |
|-------|-----------|---------|
| `Idle` | car centred, north-up | "Where to?" pill |
| `Searching` | unchanged | search panel, left 420 dp |
| `Preview` | fits the selected route | destination + alternates + **Start** |
| `Guiding` | chases the car, heading-up, car at 70% height | maneuver card · speed · ETA bar |

The map is **one instance for the whole tab** and is never torn down between phases. That is
what lets the camera fly between them instead of cutting.

## Buttons & behavior

| Control | Tap | Long-press | Moving |
|---------|-----|-----------|--------|
| "Where to?" pill | Open search | — | allowed |
| Search result row | Request routes → Preview | — | allowed |
| Route option | Select that alternate | — | allowed |
| **Start** (76 dp) | Begin guidance | — | allowed |
| Cancel ✕ (preview) | Back to Idle | — | allowed |
| Re-centre ⌖ | Toggle chase camera ↔ whole-route overview | — | allowed |
| 🔊 mute | Toggle voice guidance | — | allowed |
| ✕ end route (red) | Stop guidance → Idle | — | allowed |
| Error banner ✕ | Dismiss | — | allowed |

Guidance ends by itself within 25 m of the destination — leaving a "0 m, turn right" card up
after the car has stopped is how this screen goes stale.

## Data

- `GeocodingService.search` — debounced 280 ms, biased to the car's position.
- `RoutingService.routes` — asks Valhalla for 2 alternates; labels are relative
  ("fastest route", "+6 min") and can only be assigned once all of them are parsed.
- `LocationSource.positions` — cold flow at 10 Hz. Cumulative distances for the active route
  are cached in `NavRepository`; recomputing hundreds of haversines per tick would be waste.
- Real GNSS is already written (`AndroidLocationSource`, plain `LocationManager` — *not*
  FusedLocationProvider, which lives in Play Services). Switch with
  `NavConfig.locationMode = GNSS`.

## Animation levels

`NavMotion.level` — build and demo at `RICH`, measure on the Pi, then flip to `SHOWCASE` if
the frame graph has room. No composable defines its own duration.

| | `RESTRAINED` | `RICH` (default) | `SHOWCASE` |
|-|-------------|------------------|------------|
| Panel enter/exit | ✅ | ✅ | ✅ |
| Flowing route dashes | — | ✅ | ✅ |
| Puck pulse | — | ✅ | ✅ |
| Rolling numerals | — | ✅ | ✅ |
| Camera easing | snap | 260 ms | 260 ms |
| Route draws itself in | — | — | ✅ (MapLibre only) |
| Glass shimmer | — | — | ✅ (preview panel only) |
| Camera tilt | 0° | 0° | 45° |

The two `SHOWCASE` extras are deliberately narrow. The route draw-in runs on preview only — a
driver mid-route needs the line *now*, not in 720 ms — and the Canvas fallback skips it. The
shimmer repeats forever, so it is confined to the preview panel, which is transient and never
on screen while the car is moving.

Every level obeys the README's rule: **transform and opacity only**. The trip-progress rail
scales with `graphicsLayer` rather than resizing with `fillMaxWidth(fraction)` for exactly
this reason.

## Google as a backup

`data/nav/google/GoogleNavProviders.kt` holds `GooglePlacesGeocodingService` and
`GoogleDirectionsRoutingService`. They implement the real interfaces and `TODO()` with the
exact endpoint, the response mapping, and the two traps (Directions uses polyline precision
**5**, Valhalla uses **6**; Directions has no shape indices, so step boundaries must be
snapped with `RouteMath.snapToRoute`). Flip with `NavConfig.stack = GOOGLE`.

They are empty on purpose: the point of the seam is that the OSS stack is a choice, and the
fact that a Google implementation compiles against the same interfaces is the proof that
swapping is one file, not a rewrite.

## Attribution

ODbL requires visible credit for OSM-derived tiles, routes **and** search results. The
`© OpenStreetMap contributors` line at the bottom-left of the map card is not decoration —
do not remove it.

## Definition of done

- [x] Search returns places and biases to the car.
- [x] Route preview shows alternates with time, distance and arrival.
- [x] Guidance advances maneuvers, distance, ETA and the traveled route.
- [x] Falls back to the Canvas map with no network and no GL.
- [x] Day + Night both derive from `Tokens`, including the map style itself.
- [ ] Voice guidance — belongs to the assistant overlay (owner D); `muted` is the hand-off flag.
- [ ] Speed limits — needs Valhalla `trace_attributes`; `SpeedPuck(limitKph = …)` is ready.
- [ ] `CarUxRestrictions` lockout while moving.
