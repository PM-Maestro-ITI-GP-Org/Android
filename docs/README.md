# Motor Guard — Component Docs

Detailed AAOS implementation specs. Build order: **skeleton first**, then fill
fragments one owner at a time. Every doc lists the component's layout, **exact
button behavior** (tap / long-press / disabled / moving-vehicle state), the
CarProperty / API it touches, and its states.

| # | Doc | Owner | Scope |
|---|-----|-------|-------|
| 00 | [Skeleton](00-skeleton.md) | core | Host activity, NavRail buttons, 4 fragment containers |
| 01 | [NavRail](01-navrail.md) | core | Left rail buttons — full behavior |
| 02 | [StatusBar](02-statusbar.md) | core | Top bar indicators |
| 03 | [Home fragment](03-home.md) | A | Widgets + shortcut buttons |
| 04 | [Media fragment](04-media.md) | B | Source tabs + transport buttons |
| 05 | [Diagnostics fragment](05-diagnostics.md) | C | Hotspot tap → zoom, cards |
| 06 | [Settings fragment](06-settings.md) | E | Left sub-tabs + toggles |
| 07 | [Voice overlay](07-voice.md) | D | Wake word, states, chips |
| 07a | [Voice — implementation](07-voice-implementation.md) | D | What landed, how to run it, known gaps |

## Conventions used in every doc
- **Tap** = single touch-up inside. **Long-press** = ≥ 500 ms.
- **Disabled** buttons: 38% opacity, no ripple, `isEnabled=false`.
- **Moving lockout**: when `CarUxRestrictions.isRequiresDistractionOptimization`
  is true, the button follows the "Moving" row in its behavior table.
- Min touch target **76 dp**; ripple on every tappable surface.
- Colors from `Tokens` only — never hardcoded.
