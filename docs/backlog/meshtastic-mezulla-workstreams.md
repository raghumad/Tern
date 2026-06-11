# Meshtastic-Mezulla firmware workstreams

This file covers work on the Meshtastic firmware that runs on the
LilyGo T3 V1.6.1 board (ESP32-PICO-D4 + SX1276 LoRa + SSD1306 OLED,
Meshtastic variant `tlora-v2-1-1_6`). It is separate from the Mezulla
(Tern app) backlog which owns all phone-side tests and UI work.

The contract between phone and board is defined in
`docs/architecture/mezulla-wire-contract.md`. Both sides code to
that contract independently.

## Source and git model

Firmware repo: https://github.com/raghumad/mezulla-firmware
(forked from `meshtastic/firmware`)

- `develop` — tracks upstream Meshtastic
- `mezulla-firmware` — our work, rebased on `develop`

Local remotes (`~/src/meshtastic-firmware`):
- `origin` → `raghumad/mezulla-firmware` (push here)
- `upstream` → `meshtastic/firmware` (pull updates)

Each commit is either upstream-worthy or Mezulla-specific, never
mixed. To submit an upstream PR: cherry-pick upstream-worthy
commits onto a branch off `develop`.

## Stage 1 — Stock Meshtastic (no firmware work)

Stage 1 uses unmodified Meshtastic firmware. The only task is
flashing the stock binary onto the board, which is already done
(flash script at `tern-android/scripts/flash-mezulla.sh`).

All Stage 1 work is on the Mezulla (Tern app) side: BLE connection,
peer rendering, SOS, graceful degradation. The board just runs stock
Meshtastic and does its thing.

**Firmware deliverable:** none. Stock `.bin` flashed and verified.

## Stage 2 — Build from source + QR pairing

First firmware modifications. We fork Meshtastic, build from source
for the `tlora-v2-1-1_6` variant, and add the QR marriage protocol.

### WS-F1: Build pipeline

Set up the local Meshtastic build environment. This replaces the
stock `.bin` flash path with our own build-from-source pipeline.

**F1.1: Clone and build** — DONE (2026-05-26)

Meshtastic firmware cloned to `~/src/meshtastic-firmware`.
PlatformIO resolves all dependencies automatically. Build command:
`pio run -e tlora-v2-1-1_6`. Produces `firmware-tlora-v2-1-1_6-2.8.0.5bce26d.bin` (2.1 MB).
Build time: ~5 minutes.

Test criteria (verified):
- Build completes without errors. ✓
- Output binary: 2,108,464 bytes.

**F1.2: Flash from source** — DONE (2026-05-26)

Flash command: `pio run -e tlora-v2-1-1_6 -t upload --upload-port /dev/ttyACM0`.
Board rebooted, OLED showed Meshtastic splash.

Test criteria (verified):
- `meshtastic --info` reports `firmwareVersion: 2.8.0.5bce26d`. ✓
  (upgraded from stock 2.7.15)
- Board boots to the standard Meshtastic OLED screen. ✓
- Hardware confirmed: `hwModel: TLORA_V2_1_1P6`, node `!f9926184`.

**F1.3: Regression gate**

All Stage 1 Mezulla tests must pass against the source-built
firmware. The board must be indistinguishable from stock at the
wire protocol level.

Test criteria:
- Tern app connects over BLE, exchanges position packets,
  receives peer events — identical behavior to stock firmware.
- Flash script updated to support both stock and source-built
  paths.

### WS-F2: QR pairing firmware

Implements the board side of the QR marriage protocol defined in
`docs/architecture/mezulla-wire-contract.md`, Stage 2 extensions.

**F2.1: Ownership state in flash** — DONE (2026-05-26)

Two fields added to `DeviceState` protobuf: `mezulla_owner_id`
(max 64 chars) and `mezulla_pairing_token` (max 33 chars). Persist
via `saveToDisk(SEGMENT_DEVICESTATE)`. Empty `owner_id` = unclaimed.

Test criteria:
- On a freshly-flashed board, ownership slot reads empty. ✓
- After writing a test owner, reboot, slot persists. ✓
- Serial log confirms ownership state on each boot. ✓

**F2.2: QR code generation on OLED** — DONE (2026-05-26)

QR Version 3 (29×29 modules) at 2px/module = 58×58 pixels, encoding
`tern://p?n=<node_hex_id>&t=<32_char_token>`. Full-screen takeover
when unclaimed — no frame rotation, no indicator dots. Uses
`ricmoo/QRCode` library. "Scan to pair" label at bottom.

Test criteria:
- QR appears on OLED within 3 seconds of boot. ✓
- QR encodes the correct URL (verified by scanning with a phone). ✓
- Token is different on each boot/reset cycle. ✓
- QR is readable at 30cm in daylight (human test). ✓

**F2.3: Claim-ownership packet handler** — DONE (2026-05-26)

Listens on `PRIVATE_APP` (port 256), command byte `0x01`. Verifies
pairing token matches the QR, stores owner_id, clears QR on success.

Test criteria (untested end-to-end — needs app-side claim packet):
- Valid claim: ownership stored, QR clears, ACK sent.
  Serial log: `"[MEZULLA] claim: accepted, owner=<owner_id>"`.
- Invalid token: ownership not stored, QR persists, NAK sent.
  Serial log: `"[MEZULLA] claim: rejected, reason=token_mismatch"`.
- Claim while already owned: rejected with status `0x02`.

**F2.4: Ownership release** — DONE (2026-05-26)

Phone-initiated release via `PRIVATE_APP` command byte `0x03`.
The owner's phone sends its `owner_id`; firmware verifies it
matches the stored owner before clearing. Board reboots into QR
mode on success. Non-owner requests rejected with status `0x03`.

Physical button reset deferred to v2 Mezulla hardware — the
LilyGo T3 V1.6.1 has no user-programmable button (only RST which
hard-reboots the ESP32). See `project_mezulla_v1_hardware_limits`.

Test criteria (untested end-to-end — needs app-side release):
- Owner sends release: ownership cleared, board shows QR.
  Serial log: `"[MEZULLA] reset: ownership cleared"`.
- Non-owner sends release: rejected with status `0x03`.
  Serial log: `"[MEZULLA] release: rejected, reason=not_owner"`.

**F2.5: Ownership query response** — DONE (2026-05-26)

Command byte `0x02` on `PRIVATE_APP`. Returns status + owner_id
(empty if unclaimed). Lets the phone detect Mezulla firmware vs
stock Meshtastic (stock silently ignores the packet).

Test criteria (untested end-to-end — needs app-side query):
- Unclaimed board responds with empty owner.
- Claimed board responds with the stored owner_id.
- Stock Meshtastic board gives no response within 2 seconds.

### WS-F2 definition of done

All F2.1–F2.5 are implemented and flashed to the real board.
QR scan verified with a real phone camera. Firmware branch
`mezulla-firmware` pushed to `raghumad/mezulla-firmware`.

**Status: COMPLETE (2026-06).** The Tern app's `tern://` deep-link
handler ships and claims over BLE on port 256; claim/query end-to-end is
verified on real hardware. The app-side BDD regression gate (F1.3) is met
by the cycle + reliability suites running against the source-built
firmware (`aravisCycleTest`/`edithsGapCycleTest`/`birBillingCycleTest`,
`bleReliabilityTest`).

## Stage 2.5 — Upstream cleanup for device pairing PR

Before submitting the pairing feature as a Meshtastic PR, the
fork code needs to be generalized. This is a cleanup pass, not
new functionality — the protocol and behavior stay the same.

**Items to address:**

1. **Rename Mezulla → generic.** `MezullaOwnershipModule` →
   `DevicePairingModule`. `mezulla_owner_id` → `pairing_owner_id`.
   `mezulla_pairing_token` → `pairing_token`. `[MEZULLA]` log tags
   → `[PAIRING]`. No Tern/Mezulla branding in upstream code.

2. **URL scheme.** Replace `tern://` with a configurable scheme.
   Default to `meshtastic://pair?n=...&t=...` for upstream.
   Mezulla overrides to `tern://` via module config. Discuss with
   Meshtastic maintainers whether they want to own a scheme or
   prefer a generic format.

3. **Display-conditional QR.** Gate QR rendering on display
   presence AND resolution. Current code assumes SSD1306 128×64.
   Needs:
   - Check `HAS_SCREEN` — skip QR if no display.
   - Check display dimensions — QR Version 3 at 2px/module needs
     at least 62×62 usable pixels. Smaller displays (OLED_TINY
     64×32) can't fit it.
   - Fallback for no-display or small-display: output the pairing
     URL to serial on boot, and/or embed pairing info in the BLE
     advertisement so the phone can discover unpaired devices
     without scanning a QR.

4. **Module config flag.** Make the feature optional and off by
   default. Add a `device_pairing_enabled` flag to
   `ModuleConfig`. Users opt in via the Meshtastic app or CLI.

5. **Bench tests.** Measure impact on boot time and memory with
   the module enabled vs disabled. The QR library adds ~1 KB to
   the binary — document this.

6. **RFC/discussion.** Open a Meshtastic GitHub discussion
   proposing the device pairing module before submitting the PR.
   Reference the protocol spec from `mezulla-wire-contract.md`
   (adapted to generic naming).

This stage is not blocking — the fork works for Mezulla as-is.
Schedule this when the end-to-end pairing flow is validated
(phone scans QR → claims board → uses it).

## Stage 3 — Upstream Meshtastic PRs (Epic 02, Phase B upstream track)

Broadcast FANET/FLARM/ADS-L. See `docs/backlog/epic-02-traffic-awareness.md`,
stories 2.5–2.9. Work starts after Stage 2 is solid and the
upstream RFC is filed.

## Stage 4 — Gap-scan receive (Epic 02, Phase B fork track)

FANET/FLARM/ADS-L reception via gap-scanning. See
`docs/backlog/epic-02-traffic-awareness.md`, stories 2.10–2.13.
Lives on our Meshtastic fork.

## Test infrastructure

Firmware tests use three mechanisms:

1. **Build scripts** — automated compilation and flash. Pass/fail
   is binary: did it compile, did it flash, did the board boot.

2. **Serial log assertions** — the firmware logs structured messages
   over USB serial. A host-side script captures the log and asserts
   expected entries appear. This is the firmware equivalent of BDD
   "then" assertions.

3. **Human tests** — real hardware, real conditions. QR readability
   at arm's length, button press timing, OLED contrast in sunlight.
   Captured as photos/video. Required for every milestone that
   touches physical UX.

Serial log format (structured, grep-friendly):
```
[MEZULLA] ownership: unclaimed
[MEZULLA] qr: displayed, token=a1b2c3d4...
[MEZULLA] claim: accepted, owner=<owner_id>
[MEZULLA] claim: rejected, reason=token_mismatch
[MEZULLA] reset: ownership cleared
```
