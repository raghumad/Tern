# Meshtastic-Mezulla firmware workstreams

This file covers work on the Meshtastic firmware that runs on the
LilyGo T3 board. It is separate from the Mezulla (Tern app) backlog
which owns all phone-side BDD tests and UI work.

The contract between phone and board is defined in
`docs/architecture/mezulla-wire-contract.md`. Both sides code to
that contract independently.

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
for the LilyGo T3 S3 variant, and add the QR marriage protocol.

### WS-F1: Build pipeline

Set up the local Meshtastic build environment. This replaces the
stock `.bin` flash path with our own build-from-source pipeline.

**F1.1: Clone and build**

Clone Meshtastic firmware, resolve dependencies (PlatformIO),
build for `tlora_t3s3_v1` variant. Document the exact steps.

Test criteria:
- Build completes without errors.
- Output binary size is within 10% of stock release binary.

**F1.2: Flash from source**

Flash the source-built binary onto the real LilyGo T3. Verify
the board boots, shows the Meshtastic splash, and responds to
`meshtastic --info` with the expected version string.

Test criteria:
- `meshtastic --info` reports version built from source.
- Board boots to the standard Meshtastic OLED screen.

**F1.3: Regression gate**

All Stage 1 Mezulla BDD tests must pass against the source-built
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

**F2.1: Ownership state in flash**

Add a flash storage slot for the board's owner identity. On boot,
read the slot. If empty, the board is ownerless. If populated, the
board is married.

Test criteria:
- On a freshly-flashed board, ownership slot reads empty.
- After writing a test owner, reboot, slot persists.
- Serial log confirms ownership state on each boot.

**F2.2: QR code generation on OLED**

When the board boots ownerless, generate a QR code encoding
`tern://pair?node=<hex_node_id>&token=<random_16_byte_hex>` and
display it on the OLED. The QR must be scannable by a phone camera
at arm's length.

Test criteria:
- QR appears on OLED within 3 seconds of boot.
- QR encodes the correct URL (verified by scanning with a phone).
- Token is different on each boot/reset cycle.
- QR is readable at 30cm in daylight (human test).

**F2.3: Claim-ownership packet handler**

Listen for incoming `PRIVATE_APP` (port 256) packets. Parse the
Mezulla claim-ownership protobuf. If the token matches the
displayed QR token, store the sender as owner and clear the QR.
If token mismatch, send NAK.

Test criteria:
- Valid claim: ownership stored, QR clears, ACK sent.
  Serial log: `"ownership claimed by <owner_id>"`.
- Invalid token: ownership not stored, QR persists, NAK sent.
  Serial log: `"claim rejected: token mismatch"`.
- Claim while already owned: rejected (board must be reset first).

**F2.4: Board reset (long-press boot button)**

Long-press the boot button for 5 seconds to clear ownership from
flash, generate a new pairing token, and redisplay the QR.

Test criteria:
- After reset, ownership slot reads empty.
- New QR appears with a different token than before.
- Serial log: `"ownership reset, new pairing token generated"`.
- Previous owner's phone gracefully shows "board unpaired"
  (this is a Mezulla BDD test, not a firmware test).

**F2.5: Ownership query response**

When the board receives an ownership query packet on `PRIVATE_APP`,
respond with the current owner identity (or empty if unclaimed).
This lets the phone detect Mezulla firmware vs stock Meshtastic.

Test criteria:
- Unclaimed board responds with empty owner.
- Claimed board responds with the stored owner_id.
- Stock Meshtastic board silently ignores the query (verified
  by testing against an unmodified board).

### WS-F2 definition of done

All F2.1–F2.5 test criteria pass on the real LilyGo T3 board.
Serial logs captured as evidence. QR scan verified with a real
phone camera (human test). The Stage 1 regression gate (F1.3)
still passes — nothing in WS-F2 breaks stock Meshtastic behavior.

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
