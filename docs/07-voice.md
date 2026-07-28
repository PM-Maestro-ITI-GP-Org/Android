# 07 · Voice overlay (owner D)

**Not a fragment, not a tab.** A system overlay that floats over whatever is on screen,
Google-Assistant style. Triggered by the wake word only.

## Trigger
- Wake word **"Hey Motor Guard"** (always-on engine) → overlay animates up from the
  bottom as a listen bar.
- No launcher icon; not user-launchable from the rail.

## States
| State | Orb | Waveform | Text |
|-------|-----|----------|------|
| Idle | dim, still | flat | (hidden) |
| Listening | pulsing, ring ripples | reactive to mic level | live transcript + caret |
| Thinking | spinning shimmer | paused | last utterance |
| Speaking | pulse in sync with TTS | plays | assistant reply |

## Behavior
| Event | Result |
|-------|--------|
| Wake word | Show overlay, request AudioFocus, → Listening |
| Speech end / silence 1.5 s | → Thinking, run NLU |
| Intent resolved | Route to tab (play music, navigate, climate, call) |
| Reply done | → Speaking, then auto-dismiss after 1 s |
| Tap outside / "cancel" | Dismiss immediately, release AudioFocus |

## Rules
- Never blocks the underlying app's own audio without AudioFocus handshake.
- Overlay is dismissible at any time; dismiss returns focus to the current app.
- Requires `VoiceInteractionService` + `SYSTEM_ALERT_WINDOW` / voice-interaction binding.
