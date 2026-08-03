# 02 · StatusBar

Top bar, right-aligned system indicators. Read-only at skeleton stage.

## Items (right → left)
`signal_cellular_alt` · `wifi` · `bluetooth` · user avatar · date · clock.

## Behavior
| Item | Tap | Source |
|------|-----|--------|
| Signal | none (indicator) | telephony state |
| Wi-Fi | (optional) open Settings → Wi-Fi | `WifiManager` |
| Bluetooth | (optional) open Settings → Bluetooth | `BluetoothAdapter` |
| Avatar | none (indicator) | static |
| Clock | none | system time, 24/12h per locale |

- Indicators update from broadcasts / state flows; no polling.
- Height 54 dp; icons 18–20 dp; text uses `Tokens` fg2.
- At skeleton stage these are static; wire live state later.
