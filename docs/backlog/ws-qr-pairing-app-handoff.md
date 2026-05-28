# QR pairing — reference

Status: COMPLETE (2026-05-27) — verified end-to-end on real hardware

## How pairing works

1. Board boots unclaimed → generates random token → displays QR on
   OLED encoding `tern://p?n=<node_hex>&t=<token>` → sets BLE to
   NO_PIN mode.

2. Pilot scans QR with phone camera → Android opens Tern via
   `tern://` deep link → app parses node ID and token from URL.

3. App scans BLE for Meshtastic devices → finds the board → connects
   GATT → requests MTU 517 → discovers services → sends claim packet
   on PRIVATE_APP (port 256) with the token + a UUID owner ID.

4. Board verifies token → stores owner → switches BLE to FIXED_PIN →
   clears QR → shows normal Meshtastic UI.

5. App persists the board's node ID. After reboot, the board
   remembers the owner and boots in claimed mode.

## BLE connection sequence

Required order — skipping or reordering causes disconnection:

1. Scan for service UUID `6ba1b218-...eafd`
2. Connect GATT
3. Request MTU 517 (board expects this immediately)
4. Discover services (after `onMtuChanged` confirms MTU)
5. Find characteristics by property: WRITE=ToRadio, READ=FromRadio,
   NOTIFY=FromNum
6. Read/Write packets

This sequence applies to ALL Meshtastic BLE communication, not just
pairing. The persistent BLE connection (next milestone) uses the
same handshake.

## Wire protocol

Port: `PRIVATE_APP` = 256

| Command | Byte | Payload | Response |
|---------|------|---------|----------|
| Claim | `0x01` | `[token_len][token][owner_id]` | `0x00` OK, `0x01` token mismatch, `0x02` already claimed |
| Query | `0x02` | (empty) | `0x00` + owner_id (also triggers OLED screen dump) |
| Release | `0x03` | `[owner_id]` | `0x00` OK, `0x03` not owner |

## BLE mode transitions

| State | bluetooth.mode | Why |
|-------|---------------|-----|
| Unclaimed | NO_PIN (2) | Phone must connect without PIN to send claim |
| Claimed | FIXED_PIN (1) | Prevents unauthorized access after pairing |
| Released | NO_PIN (2) | Board ready for new owner |

## What's implemented

| Component | Location |
|-----------|----------|
| Deep link handler | `TernParaglidingActivity.kt` — `handleDeepLink()` |
| URI parser | `TernDeepLink.kt` — `TernPairLink.parse()` |
| BLE scan + connect + claim | `BlePairingService.kt` |
| Pairing orchestrator | `PairingOrchestrator.kt` |
| Claim packet codec | `MezullaPairingCodec.kt` |
| GATT UUIDs | `MeshtasticGattUuids.kt` (discovered from device, not hardcoded) |
| Settings UI | `SettingsSheet.kt` — shows paired board name + "Forget Board" |
| Firmware claim handler | `MezullaOwnershipModule.cpp` |
| QR rendering | `MezullaQrScreen.cpp` (nayuki qrcodegen, Version 2, 2px/module) |
| Screen dump | `MezullaScreenDump.cpp` — dumps SSD1306 buffer to serial |
| Screen dump decoder | `scripts/decode-mezulla-screen.py` (pyzbar + PIL) |
| On-demand screendump | `scripts/screendump.sh` (sends query, captures dump) |
| Test: BLE pairing | `BlePairingTest.kt` — node ID from pairUri arg, not hardcoded |
| Test: reset cycle | `scripts/reset-mezulla.sh` — erase + flash + QR decode |

## Remaining

- **Release (unpair) from app** — firmware handler exists (cmd 0x03),
  app "Forget Board" clears local state but doesn't send release
  packet to the board yet.
- **Persistent BLE connection** — pairing connects and disconnects.
  Keeping the connection open for position exchange is the next
  milestone (see current-focus.md).
