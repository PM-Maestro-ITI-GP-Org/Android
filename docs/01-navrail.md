# 01 · NavRail

Fixed left rail. Icon-only buttons + brand mark at the bottom.

## Layout
- Width **92 dp**, full height, `rail-bg` gradient.
- Buttons vertically centered, 60 dp box each, 10 dp gap, 76 dp hit target.
- Brand mark (`shield` + "MOTOR GUARD") pinned to bottom.

## Button behavior — per item

| Interaction | Result |
|-------------|--------|
| Tap (unselected) | Navigate to that fragment; set selected. |
| Tap (selected) | No-op (no reload). |
| Long-press | Show tooltip label (`Home`, `Media`…) for 1.5 s. |
| Disabled | Only if a fragment is unavailable (e.g. Nav deferred) → 38% opacity, no ripple. |
| Moving | All rail buttons stay enabled (navigation between apps is allowed while driving). |

## Visual states
- **Idle**: icon `FILL 0`, color `rail-fg` (46% white).
- **Selected**: icon `FILL 1`, color `rail-on` (accent), `rail-active` rounded bg,
  3 dp accent bar on the left edge.
- **Pressed**: ripple, 92% scale.

## Compose
`NavigationRail` with `NavigationRailItem`s; `selected` drives icon fill + indicator.
Selection state hoisted to `MainActivity` (`Tab` enum).
