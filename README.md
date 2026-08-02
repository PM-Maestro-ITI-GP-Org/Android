# Motor Guard — AAOS Infotainment System

Custom **Android Automotive OS (AAOS)** system UI + launcher for a **full-EV** vehicle,
running on a **Raspberry Pi 5** driving a landscape touchscreen (primary 1920×720,
reflows to 1280×720 and 1024×600).

Design language: glassmorphism · **Modern Tech / Sleek** palette · dark-centric ·
Day + Night themes from a single token source.

---

## 1. Platform & Constraints

| Item | Requirement |
|------|-------------|
| Target OS | Android Automotive OS (AOSP automotive build) |
| Hardware | Raspberry Pi 5 — modest VideoCore VII GPU |
| Display | Landscape only, horizontal dash mount |
| Resolutions | 1920×720 (primary), 1280×720, 1024×600 — and any other panel, via a uniform UI scale |
| Vehicle | Full EV — battery %, charge state, range (no ICE fuel) |
| Build | adb-flashable `userdebug` image (KonstaKANG / android-rpi) |
| Framework | Kotlin + Jetpack Compose |

### Adapting to the panel
Every dimension is drawn for the **720 dp-tall** primary target. `MotorGuardTheme` overrides
`LocalDensity` by `rememberUiScale()` — the panel's height over 720 — so `dp` and `sp` alike are
converted through it and the whole design keeps its proportions on any screen. At 1920×720 the
scale is 1.0 and nothing changes. The scale floors at 0.5 so touch targets stay usable.

### Performance budget (RPi 5)
- **One** blurred backdrop per screen, blur radius ≤ 16 dp.
- Animate **transform / opacity only** — never blur, shadow, or layout.
- Target 60 fps, degrade gracefully to 30.
- GPU acceleration via mesa `v3d`; watch overdraw with the dev overlay.

---

## 2. Design Principles

- **Strict contrast** — all text & critical icons meet WCAG AA (4.5:1).
- **Dark-mode centric** — deep charcoal/black bases reduce night glare.
- **Context-aware** — auto Day↔Night from the light sensor / `UiModeManager`.
- **Universal semantic color** — 🟢 ready/active · 🟡 caution · 🔴 critical.
- **Glanceability** — ≥ 76 dp touch targets, oversized numerals, readable in < 2 s.
- **Distraction limits** — honor `CarUxRestrictions` while the vehicle is moving.

---

## 3. Color System — Modern Tech / Sleek

| Token | Hex | Use |
|-------|-----|-----|
| Base Dark | `#121212` | App/background base (night) |
| Panel | `#161B24` | Card / glass surface |
| Primary Accent | `#56C9EF` | Electric Blue — active state, highlights |
| Secondary Accent | `#80DCF8` | Icy Cyan — gradients, secondary |
| Success | `#38D17F` | Green — ready / charging complete |
| Caution | `#F5B942` | Amber — non-critical warnings |
| Critical | `#F46C64` | Salmon/Red — emergencies |

All tokens live in **one place** (CSS variables here → a Compose `MaterialTheme`
`ColorScheme` + small `Tokens` object). Day/Night swaps one attribute.
Accent can optionally sync to the cabin ambient LED / drive mode.

---

## 4. Components / Surfaces

Each surface lists **Requires** (hardware / platform inputs) and
**Provides** (user-facing capability).

### 4.1 Launcher / Home  · `App · HOME role`
Glanceable hub that opens apps and hosts live widgets.

**Requires**
- CarLauncher HOME role on AOSP
- Live vehicle props (SoC, range) via `CarPropertyManager`
- `MediaSession` for the now-playing widget
- Weather + location provider

**Provides**
- App grid + persistent left nav rail
- Battery & range gauge rings
- Mini-map with ETA (image/nav provider)
- Now-playing + weather widgets (equal height)
- One-tap into Vehicle Status / Service
- Three separated glass cards: Map · Vehicle · Weather+Media

### 4.2 Media  · `App`
Playback from multiple sources via a segmented source switcher.

**Requires**
- USB mass-storage mount + `MediaStore` scan of the stick
- Bluetooth **A2DP** (audio) + **AVRCP** (metadata/controls)
- FM/DAB tuner hardware + RDS station data
- `MediaSession` / `MediaBrowserService` per source

**Provides**
- Source tabs: **Library · USB · Bluetooth · Radio**
- Background playback (Media3 `MediaLibraryService` + ExoPlayer) that survives leaving the app
- Library/USB: MediaStore scanning, mount detection, queue, shuffle, repeat, scrubbing
- Bluetooth: phone track metadata + transport via AVRCP
- Radio: abstract `RadioTuner` contract, awaiting hardware
- Unified transport bar & now-playing across all sources, shared with the Home widget
- **Album-art theming** — the whole app's accent follows the current cover, contrast-corrected
  to WCAG AA; the semantic green/amber/red stay fixed
- Online cover-art lookup when a file has no embedded artwork, cached on disk

### 4.3 Navigation  · `App`
Full-bleed map with destination search, route preview and turn-by-turn guidance.
**No Google Maps** — this is an AOSP image with no Play Services, so the whole stack is OSS
and provider-swappable. Details: **`docs/08-navigation.md`**.

**Requires**
- MapLibre Native + OpenFreeMap vector tiles (no key, no quota)
- Valhalla for routes, Photon for search — public fair-use instances, self-host for production
- A `LocationSource`: route simulator today, `LocationManager` GNSS when a receiver is fitted
- `INTERNET` + `ACCESS_NETWORK_STATE`

**Provides**
- Search-as-you-type destinations, biased to the car's position
- Route preview with alternates (time · distance · arrival) and one 76 dp **Start**
- Guidance: maneuver card, "then…" chip, speed puck, ETA bar, mute, end route
- Heading-up chase camera; one tap frames the whole remaining route
- Map style generated from `Tokens` — Day and Night, no second palette
- Automatic fallback to an offline Canvas map when there is no network or no GL

### 4.4 Voice Assistant  · `System overlay (NOT an app)`
Google-Assistant-style pop-over that appears **over whatever is on screen** when the
wake word is heard. No launcher icon, not user-launchable.

**Requires**
- Always-on wake-word engine (“Hey Motor Guard”)
- Microphone + `AudioFocus`
- `VoiceInteractionService` + system-window / overlay permission
- On-device STT + NLU intent routing

**Provides**
- Floating HUD over any surface; dismisses on completion
- Four states: **idle · listening · thinking · speaking** (animated orb + waveform)
- Live transcript of the utterance
- Quick-action chips: play music, navigate, set climate, call
- Hands-free intents routed to the right surface

### 4.5 Vehicle Diagnostics  · `App · live hardware`
Bound to **real** vehicle hardware. Interactive car view; **tapping any component
(battery, tires, motor, brakes, doors) zooms into it and shows its live state**.

**Requires**
- CAN bus → VHAL, read via `CarPropertyManager`
- Real sensor feeds: tire PSI, cell temp, SoC, brake wear, door/lock state
- Subscription/polling on property change
- Threshold config for alert severity

**Provides**
- Tap a component → zoom-in animation + live state card
- Per-part telemetry (PSI, temp, charge %, health %)
- Overall health score + prioritized alerts list
- Semantic severity coding (green/amber/red)
- Real-time updates as hardware values change

### 4.6 Settings  · `App`
Connectivity, theme, and system.

**Requires**
- `WifiManager` (scan/connect/state)
- `BluetoothAdapter` (pair/connect/state)
- `UiModeManager` + light sensor for auto theme
- OTA update service

**Provides**
- Wi-Fi: toggle, network list, connection state
- Bluetooth: toggle, paired-device list, roles (audio/phone)
- Theme: Day / Night picker + **auto day/night** toggle
- Accent color / ambient-LED sync
- System info & OTA (MotorGuard OS)

---

## 5. Hardware & Data Integration

| Signal / Source | Channel / API | Consumed by |
|-----------------|---------------|-------------|
| State of charge, range | `CarPropertyManager · EV_BATTERY_LEVEL` | Home gauges, Diagnostics |
| Tire pressure | `CarPropertyManager · TIRE_PRESSURE` | Diagnostics, alerts |
| USB / on-device media | `MediaStore` (per storage volume) + Media3 `MediaLibraryService` | Media, Home widget |
| Phone audio | Bluetooth A2DP / AVRCP | Media (Bluetooth) |
| FM / DAB radio | `RadioManager` / tuner HAL + RDS | Media (Radio) |
| Wake word + speech | `VoiceInteractionService` + STT/NLU | Voice overlay |
| Ambient light / time | `UiModeManager` NIGHT / light sensor | Day↔Night theme |
| Wi-Fi / Bluetooth | `WifiManager` / `BluetoothAdapter` | Settings |
| Map tiles · routes · search | OpenFreeMap · Valhalla · Photon (HTTPS, all OSS) | Navigation |
| Vehicle position | `LocationSource` — simulator now, `LocationManager` GNSS later | Navigation |

---

## 6. App architecture — one app, tabbed fragments

Single AAOS app. `MainActivity` hosts a `NavRail` + one `FragmentContainerView`;
each rail tab swaps a Fragment. Team members each own one fragment and extend the
shared skeleton (theme tokens, `core/components`, `CarDataRepository`).
Full tree and stub files: **`vendor/motorguard/`**.

### Fragment features (what each surface actually does)

| Fragment | Owner | Features | Reads | Writes |
|----------|-------|----------|-------|--------|
| **HomeFragment** | A | Battery+range gauge rings · mini-map w/ ETA · weather + now-playing widgets (equal height) · Vehicle/Service shortcuts · 3 separated cards (Map·Vehicle·Weather+Media) | SoC, range, MediaSession, weather | none (launch intents) |
| **MediaFragment** | B | Source tabs **Library · USB · Bluetooth · Radio** · MediaStore browse/queue · BT metadata+transport (AVRCP) · Radio band/seek/presets/RDS · unified transport bar · album-art theming | MediaSourceManager, MediaConnection | transport, active source, presets |
| **DiagnosticsFragment** | C | Interactive car w/ tappable hotspots · **tap a part → zoom-in + live state card** · per-part PSI/temp/charge/health · health score + alerts · semantic green/amber/red · live property updates | TIRE_PRESSURE, EV_BATTERY_LEVEL, temps, brakes, doors (VHAL) | ack/dismiss alerts |
| **VoiceOverlayService** | D | **NOT a tab** — wake-word ("Hey Motor Guard") bottom listen bar over any surface · idle/listening/thinking/speaking · live transcript · routes intents | mic, STT/NLU | routes to tabs |
| **SettingsFragment** | E | Wi-Fi toggle+list+state · Bluetooth toggle+paired list+roles · Theme Day/Night + auto day-night · accent/ambient-LED sync · OS/OTA/About | wifi/bt state, UiMode, OTA | wifi connect, bt pair, theme, accent |
| **NavFragment** | — | Destination search (Photon) · route preview w/ alternates (Valhalla) · turn-by-turn guidance · MapLibre vector map styled from `Tokens` · offline Canvas fallback | LocationSource (simulated → GNSS), network | active route, guidance state |

### Shared skeleton (don't fork — extend)
- `ui/core/theme/` — `Tokens` / `Color` / `Type`: **single source of truth**; Day+Night free.
- `ui/core/components/` — `GlassCard`, `GaugeRing`, `StatCard`, `NavRail`, `StatusBar`, `AppTile`, `AlertBadge`, `Toggle`.
- `data/CarDataRepository` — the **only** place that touches `CarPropertyManager`; fragments observe StateFlows.

### Component → Compose mapping
| Component | Compose |
|-----------|---------|
| GlassCard | `Surface` + `graphicsLayer` blur (`RenderEffect.createBlurEffect`) |
| GaugeRing | `Canvas { drawArc(useCenter=false, cap=Round) }` + `animateFloatAsState` |
| NavRail | `NavigationRail` |
| StatusBar | `CarSystemBar` / custom `Row` |
| PlaylistRow · AlertBadge | `LazyColumn` items |
| VoiceOrb | `Canvas` + `rememberInfiniteTransition` |
| Vehicle data | `CarPropertyManager` + VHAL |

---

## 7. Files in this project

| File | Purpose |
|------|---------|
| `Meow AAOS.dc.html` | Live prototype shell — surface / theme / resolution switcher + Reflow, Day/Night, Tips |
| `MeowScreen.dc.html` | The reusable screen component: tokens, NavRail, StatusBar + every surface |
| `System Design.dc.html` | Visual system-design spec (this README in UI form) |
| `vendor/motorguard/` | Skeleton AAOS app tree — Android.bp, manifest, MainActivity, one Fragment per tab + shared core |
| `assets/map.png` | Dark map image used by Home + Nav |
| `image-slot.js` | Drag-drop image slots (car render, album art) |

> Placeholders still open for your art: **car render** (transparent PNG), **album art**,
> and an optional **weather photo**.
