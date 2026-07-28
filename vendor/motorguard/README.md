# Motor Guard — vendor tree

Single AAOS app that acts as the system launcher. `MainActivity` hosts a left
`NavRail` + one `FragmentContainerView`; each nav button swaps a Fragment.
Team of 2–3: everyone extends this skeleton (theme tokens, shared components,
`CarDataRepository`) and owns one fragment.

```
vendor/motorguard/MotorGuard/
├── AndroidManifest.xml
├── res/
│   ├── values/        colors.xml · themes.xml · dimens.xml · strings.xml
│   └── values-night/  colors.xml
└── java/com/motorguard/ivi/
    ├── MainActivity.kt
    ├── data/CarDataRepository.kt
    └── ui/
        ├── home/HomeFragment.kt
        ├── media/MediaFragment.kt
        ├── diagnostics/DiagnosticsFragment.kt
        ├── settings/SettingsFragment.kt
        └── voice/VoiceOverlayService.kt
```

---

## Manifest

### `AndroidManifest.xml`
**Why it's needed:** without it the app is neither a launcher nor able to read the
car. It declares the app as a platform app (`sharedUserId="android.uid.system"`) so
the Car APIs are usable, gives `MainActivity` the `HOME`+`LAUNCHER` intent-filter so
it boots as the dashboard home, registers `VoiceOverlayService` as a system voice
service (no icon), and requests every car/hardware permission the features rely on
(`CAR_ENERGY`, `CAR_TIRES`, `CONTROL_CAR_CLIMATE`, `RECORD_AUDIO`,
`SYSTEM_ALERT_WINDOW`, `BLUETOOTH_CONNECT`, `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION`).
**Touch when:** a feature needs a new permission, or you add a new activity/service.

---

## Resources — `res/`

The theme layer. These files are the **single source of truth** for color, type, and
spacing; every screen reads from them, so Day/Night and rebrands change here only.

### `res/values/colors.xml`
**Why it's needed:** defines all **Day** color tokens (base/panel/glass, rail, text,
accent, semantic green/amber/red) in the Modern Tech palette. Components reference
`@color/accent` etc. instead of literals — that's what makes theming swappable.
**Touch when:** changing the light palette or adding a new semantic color.

### `res/values-night/colors.xml`
**Why it's needed:** the **Night** override of the same token names. The platform picks
this automatically under `UiMode` NIGHT / light sensor, so night mode needs zero code.
**Touch when:** tuning dark-mode contrast/glare; must keep the same names as `values/`.

### `res/values/themes.xml`
**Why it's needed:** the `Theme.MotorGuard` (Material3 **DayNight**) that ties the token
colors to Material roles and drives the automatic day↔night swap. Without a DayNight
theme the two `colors.xml` files would never switch.
**Touch when:** changing Material role mapping, status/nav bar colors, or the base parent.

### `res/values/dimens.xml`
**Why it's needed:** spacing/radius/elevation tokens (rail width, **76dp touch min**,
26dp card radius, 16dp blur cap). Centralizing them keeps every component consistent and
enforces the safety/perf rules (touch size, blur budget).
**Touch when:** adjusting global spacing, corner radius, or the blur cap.

### `res/values/strings.xml`
**Why it's needed:** user-facing text (app name, tab labels, wake word) in one place for
i18n. No hardcoded strings in code.
**Touch when:** copy changes or adding a locale.

---

## Kotlin — `java/com/motorguard/ivi/`

### `MainActivity.kt`
**Why it's needed:** the skeleton itself — the one host every fragment plugs into. Owns
the `Tab` enum, lays out the NavRail + `fragment_container`, and `show(tab)` swaps
fragments. Nothing renders without it.
**Touch when:** adding/removing a tab (enum + `show()` branch + rail button), or changing
the swap strategy (`replace` vs `show/hide`).

### `data/CarDataRepository.kt`
**Why it's needed:** the **only** place allowed to talk to `CarPropertyManager`. It turns
raw VHAL properties into StateFlows so fragments observe live data without each one
touching the HAL (prevents duplicated, racy hardware access). On the Pi it's backed by the
mock VHAL until real CAN is connected.
**Touch when:** exposing a new car signal, or switching mock↔real data source.
Requires the matching car permission in the manifest.

### UI fragments — `ui/`
Each is self-contained; an owner extends only their package. Detailed button behavior is
in the project `docs/`.

| File | Why it's needed | Reads | Writes |
|------|-----------------|-------|--------|
| `home/HomeFragment.kt` | The launcher hub users land on — glanceable widgets + shortcuts into other tabs. | SoC, range, MediaSession, weather | none |
| `media/MediaFragment.kt` | Playback across the three real sources (USB/Bluetooth/Radio) behind one transport bar. | MediaSourceManager, MediaSession | transport, source, presets |
| `diagnostics/DiagnosticsFragment.kt` | The reason for real hardware — tap a car component → zoom + live state; health & alerts. | TIRE_PRESSURE, EV_BATTERY_LEVEL, temps, doors | ack alerts |
| `settings/SettingsFragment.kt` | Connectivity + theme control the driver expects (Wi-Fi, BT, Day/Night, system). | wifi/bt state, UiMode | wifi connect, bt pair, theme |
| `voice/VoiceOverlayService.kt` | Hands-free control that must float over any screen — so it's a service, not a tab. | mic, STT/NLU | routes intents |

**Depends on / used by:** all fragments ← `MainActivity` + `CarDataRepository`;
`VoiceOverlayService` is launched by the platform via the manifest, not by `MainActivity`.

---

## Team rules
- **Never hardcode color** — use `@color/*` / `Tokens`; Day+Night come free.
- **Never read `CarPropertyManager` in a fragment** — go through `CarDataRepository`.
- **Reuse shared components** — no fragment rolls its own card/gauge/toggle.
- Touch only your `ui/<name>/` package + shared token/core files via PR.
