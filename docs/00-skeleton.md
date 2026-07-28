# 00 · Skeleton

The shell everyone builds on. Nothing domain-specific lives here — just the host,
the left NavRail, and 4 empty fragment containers. Loadable content is each owner's
job; the skeleton only proves navigation works.

## Structure
```
MainActivity (single Activity, HOME/LAUNCHER)
├── NavRail        (left, fixed 92dp)   — 4 buttons + brand mark
└── FragmentContainerView (fills rest)  — hosts ONE fragment at a time
```

Four registered destinations (placeholder content is fine at skeleton stage):
`HOME`, `MEDIA`, `NAV`, `DIAGNOSTICS`.

## NavRail buttons (skeleton behavior)

| Button | Icon | Tap | Selected state | Moving |
|--------|------|-----|----------------|--------|
| Home | `home` | Load `HomeFragment` | Fill icon, accent bar, `rail-active` bg | allowed |
| Media | `music_note` | Load `MediaFragment` | same | allowed |
| Nav | `navigation` | Load `NavFragment` | same | allowed |
| Diagnostics | `electric_car` | Load `DiagnosticsFragment` | same | allowed |

Rules:
- Exactly **one** button selected at a time; re-tapping the selected button is a no-op
  (does NOT reload the fragment).
- Switching keeps each fragment's state — use `FragmentTransaction.replace` with a
  back-stack-less swap, or `show/hide` if you want to keep them all alive (preferred
  for instant switching; costs more memory — fine on Pi 5 for 4 fragments).
- No transition animation heavier than a 150 ms cross-fade (perf budget).

## Contract for fragment owners
- Your fragment inflates into `R.id.fragment_container`. Assume it may be created once
  and re-shown many times — do heavy setup in `onViewCreated`, refresh live data in
  `onResume`.
- Pull vehicle data from `CarDataRepository` (injected), never `CarPropertyManager` directly.
- Use `core/components` for every card/gauge/toggle/button.

## Definition of done (skeleton)
- [ ] App boots as HOME on the Pi image.
- [ ] All 4 rail buttons switch fragments; selected state correct.
- [ ] Day/Night token swap applies to rail + container.
- [ ] Fragments can be empty placeholders (a centered label is enough).
