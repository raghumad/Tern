# QR pairing — app-side handoff from firmware

Status: ready for app work
Relates to: Epic 01 WS5 Phase 2 (stories 5.2.4–5.2.6)

## What the firmware already does

The Meshtastic-Mezulla firmware (branch `mezulla-firmware` in
`~/src/meshtastic-firmware`) is built, flashed, and running on the
real LilyGo T3 V1.6.1. The following are implemented and working:

1. **QR code on OLED when unclaimed.** On boot, if no owner is
   stored, the board displays a QR code encoding:
   ```
   tern://p?n=<8-char-hex-node-id>&t=<32-char-hex-pairing-token>
   ```
   Example: `tern://p?n=4a312aaa&t=e7f3a1b2c4d5e6f78901a2b3c4d5e6f7`

2. **Ownership state in flash.** Two fields in DeviceState protobuf:
   `mezulla_owner_id` (max 64 chars) and `mezulla_pairing_token`
   (max 33 chars). Persist across reboots.

3. **Claim-ownership packet handler.** Listens on `PRIVATE_APP`
   (Meshtastic port 256). Accepts a claim packet, verifies the
   pairing token matches the QR, stores the owner, clears the QR.

4. **Ownership query.** Also on port 256. Phone can ask "who owns
   this board?" and get back the owner ID (or empty if unclaimed).

5. **QR-only mode.** When unclaimed, the OLED shows only the QR —
   no frame rotation, no flickering.

## What the Tern app needs to implement

### 1. Register `tern://` deep link handler

**Android manifest:** Register Tern as the handler for `tern://`
URLs using an intent filter with `android:scheme="tern"`.

```xml
<activity ...>
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="tern" />
  </intent-filter>
</activity>
```

Use a custom scheme (not HTTPS App Links) because pairing must
work offline at remote launch sites with no internet.

When the pilot scans the QR with the phone camera, Android should
open Tern and deliver the URL via the intent.

**Parse the URL:**
- Scheme: `tern`
- Host: `p` (short for "pair")
- Query params: `n` = node hex ID, `t` = pairing token

### 2. Pairing flow after deep link

When Tern receives a `tern://p?n=...&t=...` intent:

1. **BLE scan** for a Meshtastic device matching node ID `n`.
   The node ID maps to the Meshtastic BLE advertisement — look for
   the device whose Meshtastic node number in hex matches `n`.

2. **Connect** to the board over BLE (using the existing
   `BleConnection` / `MeshtasticConnection` abstraction).

3. **Send claim-ownership packet** on `PRIVATE_APP` (port 256):
   ```
   Payload format:
   [0x01]                         — command byte (CLAIM)
   [token_length: 1 byte]         — length of the token string
   [token: token_length bytes]    — the pairing token from the URL
   [owner_id: remaining bytes]    — a string identifying this phone
   ```
   The `owner_id` can be any stable phone-side identifier (e.g.
   a UUID generated on first Tern install).

4. **Read the response** — the board replies on the same port:
   ```
   Response format:
   [status: 1 byte]               — 0x00 = OK, 0x01 = token mismatch, 0x02 = already claimed
   [owner_id: remaining bytes]    — the stored owner (on OK or query)
   ```

5. **On success (status 0x00):**
   - Persist the board's node ID locally as the paired board.
   - The board clears its QR and starts normal Meshtastic operation.
   - Tern switches to the connected state (link UP).

6. **On failure:**
   - `0x01` (token mismatch): the QR was regenerated since scanning.
     Show "Pairing failed — try scanning again."
   - `0x02` (already claimed): another phone already claimed it.
     Show "Board is already paired. Reset the board to re-pair."

### 3. Replace Phase 1 pairing UI

Once the deep link flow works, the Phase 1 settings-screen BLE
scan UI should be replaced. Settings now only shows the currently
paired board and offers "Forget board" (which clears the local
persisted board ID — the board side requires a physical long-press
reset to become claimable again).

### 4. Ownership query (detect Mezulla vs stock)

To distinguish a Mezulla-firmware board from stock Meshtastic,
send an ownership query packet on `PRIVATE_APP`:
```
Payload: [0x02]   — command byte (QUERY)
```
- Mezulla boards respond with status + owner_id.
- Stock Meshtastic boards silently ignore `PRIVATE_APP` packets.

If no response within 2 seconds, assume stock Meshtastic.

## BLE connection sequence

The Meshtastic BLE stack requires this exact handshake order.
Skipping or reordering steps causes the board to drop the connection.

1. **Scan** for devices advertising service UUID `6ba1b218-...eafd`
2. **Connect** GATT (`device.connectGatt`)
3. **Request MTU 517** (`gatt.requestMtu(517)`) — the board expects
   this immediately after connect. Without it, the board drops the
   connection after ~2.5 seconds.
4. **Discover services** (`gatt.discoverServices()`) — only after
   `onMtuChanged` callback confirms MTU negotiation succeeded.
5. **Find characteristics by property** — ToRadio is WRITE (props=8),
   FromRadio is READ (props=2), FromNum is NOTIFY (props=18). Do not
   hardcode UUIDs — discover them from the service.
6. **Read/Write** — ToRadio for outbound packets, FromRadio for
   inbound (polled via FromNum notify).

This sequence applies to ALL Meshtastic BLE communication, not just
pairing. The `BleConnection` class must follow the same order.

## Wire protocol reference

Full protocol spec: `docs/architecture/mezulla-wire-contract.md`

Key constants:
- Port: `PRIVATE_APP` = 256 (Meshtastic `PortNum`)
- Claim command: `0x01`
- Query command: `0x02`
- Status OK: `0x00`
- Status token mismatch: `0x01`
- Status already claimed: `0x02`

## BDD scenarios for the app side

```
scenario: pilot scans QR and pairs successfully
  given Tern is installed and running
    and a Mezulla board is powered on and unclaimed (showing QR)
  when the pilot scans the QR with the phone camera
  then Tern opens via the tern:// deep link
    and Tern connects to the board over BLE
    and Tern sends a claim-ownership packet with the correct token
    and the board responds with status OK
    and Tern persists the board as paired
    and the board clears the QR and enters normal operation

scenario: pilot scans QR with stale token
  given a Mezulla board was reset after the pilot scanned the QR
    (the token changed)
  when Tern sends the claim packet with the old token
  then the board responds with status token_mismatch
    and Tern shows "Pairing failed — try scanning again"

scenario: pilot scans QR of already-claimed board
  given a Mezulla board is already claimed by another phone
  when Tern sends a claim packet
  then the board responds with status already_claimed
    and Tern shows "Board is already paired. Reset to re-pair."

scenario: Tern detects Mezulla vs stock Meshtastic
  given Tern is connected to a board over BLE
  when Tern sends an ownership query on PRIVATE_APP
  then a Mezulla board responds with status + owner_id
    and a stock Meshtastic board gives no response within 2 seconds

scenario: settings screen shows paired board
  given Tern is paired with a Mezulla board
  when the pilot opens settings
  then the paired board's name and ID are shown
    and a "Forget board" option is available
    and no BLE scan UI is shown
```

## Firmware-side status

| Item | Status | Evidence |
|---|---|---|
| QR on OLED | Done | Visible on real board, scannable |
| Ownership in flash | Done | Persists across reboots |
| Claim handler | Done, untested end-to-end | Needs app-side claim packet to verify |
| Ownership query | Done, untested end-to-end | Needs app-side query to verify |
| Board reset (long-press) | Not yet implemented | F2.4 still pending on firmware side |

## What blocks this

- Nothing on the firmware side blocks app work. The board is
  running and showing the QR.
- The `tern://` deep link registration is the critical first step —
  without it, scanning the QR does nothing (which is the current
  state).
- `BleConnection` already exists in the codebase and handles
  Meshtastic packet exchange. The claim packet is just a new
  payload on `PRIVATE_APP` through the same connection.
