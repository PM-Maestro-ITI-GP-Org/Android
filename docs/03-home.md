# 03 · Home fragment (owner A)

Glanceable hub of read-only widgets + shortcut buttons. Writes nothing; only reads
state and launches other tabs.

## Widgets
Three cards: **Map** · **Vehicle** · **Weather + Media** (last two equal height).

## Buttons & behavior

| Control | Tap | Long-press | Moving |
|---------|-----|-----------|--------|
| Map card | Open Nav tab | — | allowed |
| Battery ring | Open Diagnostics → Battery | — | allowed |
| Range ring | Open Diagnostics → Battery | — | allowed |
| **Vehicle Status** btn | Open Diagnostics | — | allowed |
| **Service** btn | Open service schedule | — | allowed |
| Weather widget | (optional) Weather screen | — | allowed |
| Now-playing ▸‖ | Play/pause active session | — | allowed |
| Now-playing ⏮ ⏭ | Prev / next track | — | allowed |
| ♥ favorite | Toggle like on current track | — | disabled while moving |

## Data
- `EV_BATTERY_LEVEL`, `RANGE_REMAINING` → gauge rings (animate on change).
- Active `MediaSession` → now-playing title/artist/art + transport.
- Weather/location provider → temp + condition.

## States
- No media session → now-playing shows "Nothing playing", transport disabled.
- Data unavailable → gauge shows `--`, not 0.
