# Firmware handoff: QR code not readable from OLED

## Problem

The Mezulla board's OLED QR code cannot be scanned by any phone camera
or QR scanner app, including Tern's own in-app scanner (ZXing). The
same scanner reads QR codes from a computer screen perfectly.

## Evidence

- Tested with: phone native camera, Google Lens, standalone QR scanner
  app, Tern's built-in ZXing scanner
- All fail on the OLED QR
- All succeed on the same data rendered as a QR on a computer screen
- The QR data: `tern://p?n=4a312aaa&t=e7f3a1b2c4d5e6f78901a2b3c4d5e6f7`
  (54 characters)

## Likely cause

54 characters requires QR version 4+ (~33x33 modules minimum). On the
LilyGo T3 V1.6.1's 0.96" 128x64 OLED, each QR module is only 1-2
pixels. Phone cameras can't resolve modules that small at any distance.

## Possible fixes (firmware side)

1. **Shorter payload** — compress the token. 16 hex chars instead of
   32 would halve the QR density. Or use base64.
2. **Larger display** — the T3 V1.6.1 OLED is 128x64. A 128x128 or
   larger display would double the module size.
3. **Use the full screen** — ensure the QR fills the entire OLED with
   no status bar or frame consuming pixels.
4. **Error correction level** — try Level L (7%) instead of Level M
   (15%) to reduce module count for the same data.
5. **Contrast/inversion** — verify white modules on black background
   (not inverted). Some OLEDs render inverted by default.

## App side status

The Tern app's QR scanner is working. Once the board produces a
readable QR, the full flow will work: scan → parse → pair.

## Test QR for development

Generate a test QR on any computer:
```bash
qrencode -o test-qr.png -s 10 -m 4 'tern://p?n=4a312aaa&t=e7f3a1b2c4d5e6f78901a2b3c4d5e6f7'
```
