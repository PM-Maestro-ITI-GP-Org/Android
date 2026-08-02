# 04 · Media fragment (owner B)

Multi-source playback. A segmented **source switcher** picks Library / USB / Bluetooth / Radio;
the transport bar is shared across all four.

Playback lives in a **background service**, not in the fragment — see [Architecture](#architecture).

## Source tabs

| Tab | Tap | Requires | Empty state | Status |
|-----|-----|----------|-------------|--------|
| Library | Scan + show on-device audio | `READ_MEDIA_AUDIO` | "No music on this device" | **built** |
| USB | Scan + show the stick | Removable volume mounted | "Insert USB drive" | **built** |
| Bluetooth | Show phone's now-playing | A2DP + AVRCP connected | "Connect a phone" | **built, untested** |
| Radio | Show tuner + presets | Tuner HAL | "No tuner on this hardware" | **abstract only** |

Switching source pauses and clears the previous one (single AudioFocus owner). That is enforced
in the service, which holds the one player.

## Architecture

```
MotorGuardMediaService (MediaLibraryService)   ← survives the Activity
   └── ExoPlayer                               ← audio focus, media buttons, notification
        ▲                         ▲
   MediaConnection (MediaController, process singleton)
        ▲                         ▲
   MediaScreen               NowPlayingCard (Home)
```

The Media tab and the Home widget are two views of **one** session. Giving each its own
controller is how they end up showing different tracks; they observe the same `MediaConnection`.

A `MediaLibraryService` rather than a plain `MediaSessionService` because on AAOS a media app is
expected to be browsable — the car's media UI, the assistant and any other controller can walk
root → source → tracks without our Activity existing.

### The three sources are not the same kind of thing

This is the idea the whole `data/media` package is shaped around, expressed as `PlaybackKind`:

| Source | Kind | What that means |
|--------|------|-----------------|
| Library, USB | `LOCAL_PLAYER` | Files. Our ExoPlayer opens them. Real seeking, queue, shuffle. |
| Bluetooth | `EXTERNAL_SESSION` | The *phone* owns the audio. We mirror AVRCP metadata and send transport commands; we never hold the stream. Hence no browsable list. |
| Radio | `TUNER` | Hardware. No file, no session, no duration, no queue — a frequency. |

Flattening these into one "source" interface that pretends they all have a track list is how
automotive media code rots. `PlaybackKind` lets the UI stay uniform while the plumbing stays
honest.

## Transport buttons

| Button | Tap | Long-press | Disabled | Moving |
|--------|-----|-----------|----------|--------|
| ▶/‖ Play/Pause | Toggle playback | — | if no track | allowed |
| ⏮ Previous | <3 s → previous track; else restart | Seek back (hold) — *not built* | — | allowed |
| ⏭ Next | Next track | Seek fwd (hold) — *not built* | at queue end | allowed |
| 🔀 Shuffle | Toggle shuffle | — | Radio | allowed |
| 🔁 Repeat | off → all → one | — | Radio | allowed |
| Progress bar | Tap to seek; drag the thumb | — | Radio / BT if unsupported | scrub **disabled while moving** — *not wired* |
| Track row | Play that track, queue = the visible list | Context menu — *not built* | — | list capped while moving — *not wired* |

Glyphs are 24–30 dp inside **76 dp** touch targets, so the row is slightly wider than the mock.
The touch-target rule wins.

## Album-art theming

The media screen and the Home widget sit inside `AlbumThemedContent`, which derives the accent
from the current cover with `androidx.palette`. Everything else — nav rail, status bar,
diagnostics severity colours — keeps the Modern Tech palette. A green/amber/red safety language
that shifts hue with whatever is playing stops being a language.

The part that matters is **contrast correction**. Palette returns whatever is in the artwork, and
plenty of covers are near-black or muddy maroon; used raw they are unreadable on `#161B24`. Every
extracted colour is stepped along the HSL lightness axis until it clears **WCAG AA (4.5:1)**
against the panel it will actually sit on, preserving hue. `AlbumPaletteTest` pins this.

The HSL conversion is hand-rolled rather than `androidx.core.graphics.ColorUtils`, because that
delegates to `android.graphics.Color` — a stub in JVM unit tests — and this guarantee is worth
being able to test without Robolectric.

## Radio: what "abstract" means here

`RadioTuner` fixes the interface (bands, tune, seek, presets, RDS) and `UnavailableRadioTuner` is
the current implementation: every method is a deliberate no-op, so the tab is reachable and shows
"No tuner on this hardware" rather than crashing. Implementation notes for the real thing — which
`RadioManager` calls to make, the signature|privileged permission it needs, where RDS arrives —
are in the `RadioTuner` KDoc.

Deliberately not a fake that pretends to tune: that would look finished in a demo and mislead
whoever picks it up.

## Data

- `MediaSourceManager` — process singleton, owns the four sources and which is active.
- `MediaStoreQuery` — one query, parameterised by storage volume. Library and USB differ only by
  which volumes they ask for.
- `AlbumArtLoader` — `loadThumbnail` first, album-art URI as fallback, LRU-cached by bytes.
- `MediaConnection` — the `MediaController`, plus a 500 ms ticker (the player reports position
  only when something else changes, so without it the scrubber would not move).

## Permissions

`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (required from API 34),
`POST_NOTIFICATIONS` (API 33+), `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` (split at API 33),
`BLUETOOTH_CONNECT` (runtime from API 31). The audio permission is requested by the Media screen
on entry; the library is re-read on grant.

## Definition of done

- [x] Background playback that survives leaving the tab and the app.
- [x] Library and USB sources with real MediaStore scanning and mount detection.
- [x] Transport, scrubbing, shuffle, repeat, queue with the playing row's equaliser.
- [x] Home now-playing widget driven by the same session.
- [x] Album-art theming, contrast-corrected and unit-tested.
- [ ] Verify on hardware — none of this has been run on a device.
- [ ] Bluetooth AVRCP metadata mirroring (availability is built; now-playing needs
      `MediaSessionManager`, which wants a privileged permission on AAOS).
- [ ] Radio against a real tuner.
- [ ] `CarUxRestrictions` lockouts while moving.
- [ ] Long-press seek and the track context menu.
