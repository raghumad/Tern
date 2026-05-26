# Human test: deep link pairing

## What this tests

The full QR scan → Tern opens → pairing flow. This is the pilot's
first-time experience connecting their phone to a Mezulla board.

## Prerequisites

- Tern installed on a real Android phone (debug build is fine)
- Mezulla board powered on and unclaimed (QR visible on OLED)
- Phone and board within BLE range (~10m)

## Steps

1. Open the phone's camera app (not Tern)
2. Point it at the Mezulla board's OLED QR code
3. The camera should recognize the `tern://` URL and offer to open it
4. Tap the link — Tern should launch
5. Tern should connect to the board over BLE and send a claim packet
6. The board's OLED should change from QR to normal status display
7. Tern should show the board as paired (no error, no further action)

## What to capture

- **Phone screen recording:** start recording BEFORE step 1, stop
  AFTER step 7. Save as `phone-recording.mp4`
- **Board OLED photo (before):** showing the QR code. Save as
  `board-before.jpg`
- **Board OLED photo (after):** showing normal status (QR gone).
  Save as `board-after.jpg`
- **logcat:** run `adb logcat -s TernParaglidingActivity MezullaPairingCodec BleConnection VideoHelper` during the test. Save as `logcat.txt`

## Pass criteria

- [ ] Camera recognizes the QR as a `tern://` URL
- [ ] Tapping the link opens Tern (not a browser)
- [ ] Tern connects to the board (logcat shows BLE connection)
- [ ] Claim packet sent with correct token (logcat shows claim)
- [ ] Board responds OK (logcat shows status 0x00)
- [ ] Board OLED changes from QR to status display
- [ ] Tern persists the board ID (restart app, board auto-connects)

## Results

Date:
Phone model:
Android version:
Board firmware version:
Tern build:

### Outcome: (PASS / FAIL / PARTIAL)

Notes:
