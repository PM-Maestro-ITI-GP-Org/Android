# 04 · Media fragment (owner B)

Multi-source playback. A segmented **source switcher** picks USB / Bluetooth / Radio;
the transport bar is shared across all three.

## Source tabs

| Tab | Tap | Requires | Empty state |
|-----|-----|----------|-------------|
| USB | Scan + show library | USB mounted, MediaStore | "Insert USB drive" |
| Bluetooth | Show phone's now-playing | A2DP + AVRCP connected | "Connect a phone" → Settings |
| Radio | Show tuner + presets | tuner HAL | "No signal" |

Switching source pauses the previous source (single AudioFocus owner).

## Transport buttons

| Button | Tap | Long-press | Disabled | Moving |
|--------|-----|-----------|----------|--------|
| ▶/‖ Play/Pause | Toggle playback | — | if no track | allowed |
| ⏮ Previous | Restart track; <3 s → prev track | Seek back (hold) | — | allowed |
| ⏭ Next | Next track | Seek fwd (hold) | at queue end (USB) | allowed |
| 🔀 Shuffle | Toggle shuffle | — | Radio | allowed |
| 🔁 Repeat | off → all → one | — | Radio | allowed |
| Progress bar | Seek to point | — | Radio/BT if unsupported | scrub **disabled while moving** |
| Playlist row | Play that track | Context menu (queue/remove) | — | list **capped/locked while moving** |

## Radio-specific
- Band toggle **FM / DAB**; seek ◄◄/►► auto-tunes to next station.
- **Preset**: tap = jump to station; **long-press = save current station** to that slot.
- RDS station name + text shown when broadcast.

## Data
`MediaSourceManager` exposes the active source; `MediaSession`/`AVRCP` for metadata &
controls; `RadioManager` for tuner + RDS.
