# 08 · Phone fragment (owner F)

Hands-free calling over **Bluetooth HFP**. The head unit is the *hands-free* device;
the paired handset is the *audio gateway* and is what actually places the call — the Pi
needs no telephony hardware, no SIM, no modem.

Two panes on 1920×720: **dialpad** left, **people** right. A live call takes over the
whole surface.

## Panes

| Pane | Contents | Empty state |
|------|----------|-------------|
| Dialpad | Number field · 3×4 pad · full-width call button | "Enter a number" |
| People | Segmented **Favourites · Recents · Contacts** | "No phone connected. Pair one in Settings › Bluetooth." |

Reflow: under 1100 dp width the pad narrows to 396 dp and keys drop 88 → **76 dp**
(the touch floor in README §2). Never smaller.

## Buttons

| Button | Tap | Long-press | Disabled | Moving |
|--------|-----|-----------|----------|--------|
| Digit key | Append digit | `0` → `+` | — | allowed |
| ⌫ Backspace | Delete last digit | — | hidden when empty | allowed |
| Call | Dial typed number | — | if field empty | allowed |
| Person row | Dial that person | — | — | list **capped/locked while moving** |
| Tab (Fav/Recents/Contacts) | Switch list | — | — | allowed |
| Mute | Toggle mic to the far end | — | — | allowed |
| Keypad | Show DTMF pad | — | until call connects | allowed |
| Hold | Hold / resume | — | until call connects | allowed |
| End / Decline | Disconnect | — | — | allowed |
| Answer | Accept incoming | — | ringing only | allowed |

## Call states

`DIALING → ACTIVE → ENDING` outgoing, `RINGING → ACTIVE` incoming, `HOLDING` either way.
The avatar ring pulses (scale + opacity only) while dialing or ringing; the duration
ticks in the UI off `elapsedRealtime`, so the repository never emits once per second.

## Data

`PhoneRepository` is the phone-side twin of `CarDataRepository` — the **only** place that
touches Telecom, `InCallService`, `ContactsContract`, `CallLog` or the HFP profile. Two
backends behind one interface:

| Backend | When | Notes |
|---------|------|-------|
| `MockPhoneSource` | `USE_MOCK = true` (default) | Seeded contacts/recents, simulated call. No permissions, no Bluetooth — demos on a bare image. |
| `TelecomPhoneSource` | `USE_MOCK = false` | `TelecomManager.placeCall` through the HFP `PhoneAccount`; call control via `MotorGuardInCallService` → `InCallBridge`. |

`InCallService` is constructed by the platform, so it cannot live inside the repository.
`InCallBridge` is the seam: the service pushes the raw `android.telecom.Call` in, the
repository projects it into `ActiveCall`. Nothing above the data layer ever sees a
Telecom type.

## Bring-up on the Pi (real backend)

```bash
# 1. flip the backend
#    PhoneRepository.USE_MOCK = false

# 2. the DIALER role — without it the platform never binds our InCallService
adb shell cmd role add-role-holder android.app.role.DIALER com.motorguard.ivi

# 3. runtime permissions (the fragment also asks on first show)
adb shell pm grant com.motorguard.ivi android.permission.CALL_PHONE
adb shell pm grant com.motorguard.ivi android.permission.READ_CONTACTS
adb shell pm grant com.motorguard.ivi android.permission.READ_CALL_LOG
adb shell pm grant com.motorguard.ivi android.permission.READ_PHONE_STATE
adb shell pm grant com.motorguard.ivi android.permission.BLUETOOTH_CONNECT

# 4. pair a phone, accept the contacts/PBAP sharing prompt on the handset
adb shell dumpsys bluetooth_manager | grep -i headsetclient   # expect STATE_CONNECTED
```

Contacts and recents stay empty until PBAP finishes syncing — that is the handset's
prompt to accept, not a bug in the tab.

## Voice

`PhoneVoice.handle()` runs *before* the C++ reasoning core in `VoiceOverlaySession.answer()`.
It owns "call \<name\>", "call \<digits\>", "answer", "hang up" — dialling needs the
contact list, which is an Android-side concern; the core reasons about faults, not about
who "Mona" is. Anything it doesn't recognise returns null and falls through untouched.

A voice-initiated call dials immediately (the driver already said it out loud); a `tel:`
intent from another app only prefills the pad and waits for a tap.

## Definition of done
- [ ] Phone tab opens from the rail; state survives a trip to Media and back.
- [ ] Typed number dials; recents and contacts rows dial.
- [ ] In-call surface shows duration, mute, hold, DTMF, end.
- [ ] "Hey Motor Guard, call Mona" places the call and speaks a confirmation.
- [ ] Day/Night both legible; every control ≥ 76 dp.
- [ ] Real backend: HFP connect/disconnect flips the link chip within a second.
