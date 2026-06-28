# Firmware chat context — Meshtastic-Mezulla session summary

This is a snapshot of everything decided, built, and learned in the
firmware session (2026-05-26/27). Use it to onboard a new agent.

## Repos and branches

- **Firmware:** https://github.com/raghumad/mezulla-firmware
  Branch `mezulla-firmware`, rebased on upstream `develop`
  Local: `~/src/meshtastic-firmware`
  Remotes: `origin` = raghumad fork, `upstream` = meshtastic/firmware

- **Tern app:** `~/src/Tern`, branch `mezulla`
  Docs only from this session — no app code changes

- **Commit discipline:** each commit is either upstream-worthy or
  Mezulla-specific, never mixed. Cherry-pick for upstream PRs.

## Hardware

- **Board:** LilyGo T3 V1.6.1 (silkscreen says "T3 V1.6.1")
  - MCU: ESP32-PICO-D4 (NOT ESP32-S3)
  - LoRa: SX1276
  - Display: SSD1306 128x64 OLED
  - Meshtastic variant: `tlora-v2-1-1_6`
  - Serial port: `/dev/ttyACM0`
  - Node: `!4a312aaa` (decimal 1244736170)
  - BLE name format: `007_6184` (short_name + last 2 MAC bytes)
  - BLE MAC: `F0:24:F9:92:61:86`
  - Device name: "Mezulla 007" (short: "007")
  - **No user button** — only RST (hard reboot). Physical reset
    deferred to v2 custom board.
  - 3 LEDs: blue + red (near TF card), green (between ON/OFF and RST)
  - Slide switch: battery power only, USB bypasses it

- **Test phone:** Ulefone Power Armor 14 Pro (Android 12, WiFi adb
  10.10.10.82:5555) for automated BLE tests
- **Daily phone:** Pixel 10 Pro (Android 16) for manual testing

## What was built (firmware commits on mezulla-firmware)

1. **Ownership module** — `MezullaOwnershipModule` on PRIVATE_APP
   (port 256). Claim (0x01), query (0x02), release (0x03) commands.
   Ownership persisted in DeviceState protobuf (`mezulla_owner_id`,
   `mezulla_pairing_token` fields).

2. **QR code on OLED** — nayuki/qrcodegen library (NOT ricmoo/QRCode
   which generates invalid QRs). QR Version 2 (25x25), 2px/module,
   3-module quiet zone = 62x62 pixels. Encodes
   `tern://p?n=<node_hex>&t=<8_char_token>`. Full-screen takeover
   when unclaimed (no frame rotation).

3. **NO_PIN BLE when unclaimed** — firmware sets bluetooth.mode to
   NO_PIN while showing QR. The QR token IS the authentication.
   FIXED_PIN causes silent write drops: Android GATT buffers writes
   before bonding completes, NimBLE onWrite never fires.

4. **Screen refresh on claim/release** — `screen->setFrames()` called
   after handleClaim stores ownership (exits QR mode) and after
   clearOwnership (re-enters QR mode).

5. **Welcome screen skip** — Meshtastic's welcome banner requires a
   button press to dismiss. Board has no user button. Skipped when
   unclaimed so QR appears immediately after boot.

6. **Phone-initiated release** — command 0x03, owner must send their
   owner_id to prove identity. Physical button reset deferred to v2.

## Key bugs found and fixed

### QR code not scannable
- **Root cause:** ricmoo/QRCode library (v0.0.1, 2017) generates
  invalid QR codes. 18 of 25 rows differ from reference output.
- **Evidence:** compiled both ricmoo and nayuki against same input,
  diffed row by row. nayuki matches `qrencode` exactly.
- **Fix:** replaced with nayuki/qrcodegen (MIT, single C file).

### QR quiet zone missing
- **Root cause:** 1px white border instead of QR-spec 3-4 module
  quiet zone. Scanners can't find QR boundary.
- **Evidence:** same QR without quiet zone doesn't scan even on a
  computer screen.
- **Fix:** proper 3-module quiet zone, centered on screen.

### BLE writes silently dropped
- **Root cause:** FIXED_PIN mode requires BLE bonding before
  characteristics accept writes. Android GATT returns success
  (buffered) but NimBLE onWrite never fires.
- **Evidence:** added LOG_INFO to onWrite — never fires with
  FIXED_PIN, fires immediately with NO_PIN.
- **Fix:** set NO_PIN when unclaimed. QR token provides auth.

### Screen stuck on QR after claim
- **Root cause:** handleClaim stores ownership but doesn't notify
  the screen to rebuild frames.
- **Fix:** call `screen->setFrames()` after claim and release.

### Token too long for QR Version 2
- **Root cause:** old 32-char token (16 bytes hex) produced a 54-char
  URL that doesn't fit QR V2 (max 32 bytes). Forced V3 which doesn't
  fit on 128x64 OLED at 2px/module.
- **Fix:** shortened token to 8 hex chars (4 bytes). 4 billion
  values, sufficient for physical-proximity pairing.

## Wire protocol (PRIVATE_APP, port 256)

### MeshPacket addressing (critical)

All commands sent as `ToRadio { packet: MeshPacket { ... } }`.

| Field | Value |
|---|---|
| `to` | Board's node number (0x4a312aaa) |
| `from` | 0 (firmware overwrites) |
| `id` | 0 (firmware auto-assigns) |
| `decoded.portnum` | 256 (PRIVATE_APP) |
| `decoded.payload` | Command bytes (see below) |
| `decoded.want_response` | true |
| `channel` | 0 |

### Claim (0x01)
```
Payload: [0x01][token_len:1][token:N][owner_id:rest]
Response: [status:1][owner_id:rest]
Status: 0x00=OK, 0x01=token_mismatch, 0x02=already_claimed
```

### Query (0x02)
```
Payload: [0x02]
Response: [status:1][owner_id:rest] (empty if unclaimed)
```

### Release (0x03)
```
Payload: [0x03][owner_id:rest] (must match stored owner)
Response: [status:1]
Status: 0x00=OK, 0x03=not_owner
```

## BLE details

- Service UUID: `6ba1b218-15a8-461f-9fa8-5dcae273eafd`
- ToRadio: `2c55e69e-4993-11ed-b878-0242ac120003`
- FromRadio: `2c55e69e-4993-11ed-b878-0242ac120002`
- FromNum: `2c55e69e-4993-11ed-b878-0242ac120004`
- **Single BLE client only** — Meshtastic app must be killed
- BLE name: `<short_name>_<last_2_mac_bytes>` (e.g. `007_6184`)
- NO_PIN when unclaimed, user's saved mode when claimed

## Test infrastructure

### Reset script
`~/src/meshtastic-firmware/scripts/reset-mezulla.sh`
Erases flash, flashes firmware, sets device name, captures token,
writes deep link to `~/src/Tern/docs/handoffs/mezulla-deeplink.txt`.

### Deep link file
`~/src/Tern/docs/handoffs/mezulla-deeplink.txt`
Contains the current `tern://p?n=...&t=...` URL. Updated by the
reset script after each flash. App agent reads this for automated
testing.

### Token capture script
`~/src/meshtastic-firmware/scripts/get-mezulla-token.sh`
Listens on serial for the token log line during boot.

### Build and flash
```
cd ~/src/meshtastic-firmware
pio run -e tlora-v2-1-1_6                    # build
pio run -e tlora-v2-1-1_6 -t upload --upload-port /dev/ttyACM0  # flash
```

### Full erase (clears ownership)
```
~/.platformio/penv/bin/python -m esptool --port /dev/ttyACM0 erase_flash
```
Normal reflash does NOT clear ownership — only app partition is
written, LittleFS data partition persists.

## Backlog docs updated

- `docs/architecture/mezulla-wire-contract.md` — full protocol spec
- `docs/backlog/meshtastic-mezulla-workstreams.md` — firmware stages
- `docs/backlog/ws-qr-pairing-app-handoff.md` — app-side handoff
- `docs/backlog/epic-02-traffic-awareness.md` — restructured
- `docs/handoffs/mezulla-deeplink.txt` — current QR deep link

## Decisions made

1. **QR token is 8 hex chars** (4 bytes) — fits V2 QR, sufficient
   for physical-proximity auth.
2. **NO_PIN when unclaimed** — QR token replaces BLE PIN. Upstream-
   worthy argument: QR auth is stronger than static 6-digit PIN.
3. **Phone-initiated release only** — no physical reset on v1
   hardware (no user button).
4. **Meshtastic fork model** — rebase on upstream develop, clean
   commit separation, cherry-pick for PRs.
5. **"Mezulla <ID>" branding** — BLE and OLED use Mezulla naming.
   ID "007" reserved for the user's board.
6. **Welcome screen skipped when unclaimed** — board has no button
   to dismiss it.
7. **Token regenerates every boot** — security (prevents stale
   token replay from a photo). For dev, use reset script + deep
   link file instead of scanning QR.
8. **Normal reflash doesn't clear ownership** — need `erase_flash`
   to reset the data partition.
