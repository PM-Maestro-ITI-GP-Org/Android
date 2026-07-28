# 06 · Settings fragment (owner E)

Left sub-tab list (**30%**) + detail pane (**70%**). Tapping a sub-tab swaps the pane.

## Sub-tabs
`Wi-Fi` · `Bluetooth` · `Theme & Display` · `System`. One selected at a time; selected
row gets accent bg/border + chevron.

## Wi-Fi pane
| Control | Tap | Long-press |
|---------|-----|-----------|
| Master toggle | Enable/disable Wi-Fi radio | — |
| Network row | Connect (prompt password if secured) | Forget / details |
| Connected row | check_circle shown | Disconnect |

## Bluetooth pane
| Control | Tap | Long-press |
|---------|-----|-----------|
| Master toggle | Enable/disable BT | — |
| Paired row | Connect / disconnect | Unpair / rename |
| Scan (implicit) | list refreshes while pane open | — |

## Theme & Display pane
| Control | Tap | Effect |
|---------|-----|--------|
| Day card | Select day | `UiModeManager` → DAY, disables auto |
| Night card | Select night | → NIGHT, disables auto |
| Auto day/night toggle | on/off | Follow light sensor; day/night cards become read-out |
| Accent swatch | Select accent | Update `Tokens.accent` (+ ambient-LED sync) |

## System pane
| Control | Tap |
|---------|-----|
| Check update | Query OTA service |
| About vehicle | VIN / software / licenses screen |
| Reset options | Network / apps / factory reset (confirm dialog) |

## Rules
- Toggles reflect **actual** system state (observe `WifiManager`/`BluetoothAdapter`),
  not local UI state.
- Destructive actions (Forget, Unpair, Reset) always confirm.
- Text entry (Wi-Fi password) **blocked while moving** per `CarUxRestrictions`.
