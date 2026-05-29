# Mezulla wire contract — phone ↔ board protocol

This document defines the protocol between the Tern Android app
(Mezulla) and the Meshtastic firmware running on the LilyGo T3
V1.6.1 board (Meshtastic-Mezulla). Both sides code to this contract
independently.

The contract is layered on Meshtastic's existing BLE API and
protobuf schema. Stage 1 uses stock Meshtastic wire format
exclusively. Stage 2 adds Mezulla-specific extensions (QR pairing).

## Transport

BLE GATT, using Meshtastic's standard service and characteristics:

| Characteristic | UUID | Direction | Purpose |
|---|---|---|---|
| `FromRadio` | `2c55e69e-4993-11ed-b878-0242ac120002` | Board → Phone | Board sends mesh packets to phone |
| `ToRadio` | `2c55e69e-4993-11ed-b878-0242ac120003` | Phone → Board | Phone sends mesh packets to board |
| `FromNum` | `2c55e69e-4993-11ed-b878-0242ac120004` | Board → Phone (notify) | Notification that new data is available |

TCP is the alternate transport for dev workflow (emulator can't do
BLE passthrough). Same protobuf framing, different physical layer.
The contract applies to both.

## Framing

Each BLE write/read is a Meshtastic `ToRadio` (phone → board) or
`FromRadio` (board → phone) protobuf, length-prefixed with a 4-byte
big-endian header over TCP. BLE uses MTU-chunked writes with the
Meshtastic stream reassembly protocol.

## Stage 1 — Stock Meshtastic (no firmware changes)

### Phone → Board

| Message | Meshtastic type | Port | Payload | When |
|---|---|---|---|---|
| Own position | `ToRadio.packet` → `MeshPacket` | `POSITION_APP` (3) | `Position` protobuf | GPS fix update, at configured cadence |
| SOS alert | `ToRadio.packet` → `MeshPacket` | `ALERT_APP` (11) | Sender node + last position (see below) | Pilot triggers SOS |
| Want config | `ToRadio.want_config_id` | — | Config nonce | On connect, to receive initial state |

**Position payload** (`Position` protobuf):
- `latitude_i`: sfixed32, degrees × 1e7
- `longitude_i`: sfixed32, degrees × 1e7
- `altitude`: int32, meters above MSL
- `time`: fixed32, GPS epoch seconds
- `ground_speed`: uint32, m/s
- `ground_track`: uint32, degrees × 1e5
- `PDOP`, `HDOP`, `sats_in_view`: precision metadata

**SOS payload** (`ALERT_APP`, port 11):
- `MeshPacket.priority`: `ALERT` (110)
- `MeshPacket.want_ack`: `true`
- Payload body: TBD — proposed small protobuf with sender node
  number, last-known `Position`, timestamp. Open question: use
  plaintext for stock Meshtastic interop, or protobuf for structure.
  Decide before WS2.2 freezes the format.

### Board → Phone

| Message | Meshtastic type | Port | Payload | When |
|---|---|---|---|---|
| Peer position | `FromRadio.packet` → `MeshPacket` | `POSITION_APP` (3) | `Position` protobuf | Peer broadcasts position |
| Peer identity | `FromRadio.packet` → `MeshPacket` | `NODEINFO_APP` (4) | `User` protobuf | Peer announces identity |
| Peer telemetry | `FromRadio.packet` → `MeshPacket` | `TELEMETRY_APP` (67) | `DeviceMetrics` | Peer sends battery/metrics |
| SOS from peer | `FromRadio.packet` → `MeshPacket` | `ALERT_APP` (11) | Same as SOS above | Peer triggers SOS |
| ACK/NAK | `FromRadio.packet` → `MeshPacket` | `ROUTING_APP` (5) | `Routing` protobuf | Response to want_ack packets |
| Node info | `FromRadio.my_info` | — | `MyNodeInfo` | On config request |
| Config complete | `FromRadio.config_complete_id` | — | Nonce echo | End of config dump |

### Link lifecycle

1. Phone discovers board via BLE scan (Meshtastic service UUID).
2. Phone connects GATT, subscribes to `FromNum` notifications.
3. Phone sends `ToRadio.want_config_id` with a nonce.
4. Board responds with `FromRadio.my_info`, node list, config,
   then `config_complete_id` echoing the nonce.
5. Link is UP. Phone sends position; board forwards mesh events.
6. On BLE disconnect, phone auto-reconnects silently. Board
   continues mesh operation independently.

### Peer identity mapping

Meshtastic identifies nodes by `node_number` (uint32). The `User`
payload from `NODEINFO_APP` carries optional `long_name` and
`short_name` strings. The phone uses `node_number` as the stable
key; display falls back to hex ID (`!a1b2c3d4`) when names are
absent.

## Stage 2 — Mezulla extensions (firmware modifications)

These extensions are additive — all Stage 1 behavior continues
unchanged. They use `PRIVATE_APP` (port 256) to avoid conflicting
with stock Meshtastic ports.

### QR pairing protocol

**Board side (firmware):**

On boot, if no owner is stored in flash:
1. Generate a pairing token (4 random bytes, 8 hex chars).
2. Encode `tern://p?n=<hex_node_id>&t=<8_char_token>` as a QR
   code (Version 2, ECC_LOW, nayuki/qrcodegen library).
3. Display QR on the OLED (full-screen, no frame rotation).
4. Disable BLE advertising timeout (advertise indefinitely).

On receiving a claim-ownership packet (see below):
1. Verify the token matches the one displayed on the QR.
2. Store the claiming phone's identity in flash as the owner.
3. Clear the QR from the OLED, resume normal Meshtastic screens.
4. Restore BLE advertising timeout to default (60 seconds).

On receiving a release-ownership packet:
1. Verify the sender is the current owner.
2. Clear owner from flash.
3. Generate new pairing token and redisplay QR.

Physical button reset is deferred to v2 hardware (LilyGo T3
V1.6.1 has no user button, only RST = hard reboot).

### MeshPacket addressing (critical for all Mezulla commands)

All Mezulla commands are sent as standard Meshtastic `ToRadio`
protobufs wrapping a `MeshPacket`. The addressing fields MUST be
set correctly or the packet will be routed to the mesh instead of
being handled locally by the firmware module.

| MeshPacket field | Value | Why |
|---|---|---|
| `to` | Board's node number (uint32 from QR `n` param, e.g. `0x4a312aaa`) | Packet must be addressed to this board, not broadcast |
| `from` | 0 (firmware overwrites) | Don't set — firmware handles it |
| `id` | 0 (firmware auto-assigns) | Don't set — firmware handles it |
| `decoded.portnum` | `PRIVATE_APP` (256) | Routes to MezullaOwnershipModule |
| `decoded.payload` | Command-specific bytes (see below) | Raw bytes, not protobuf-within-protobuf |
| `decoded.want_response` | `true` | Triggers `allocReply()` so the board sends a response back to the phone |
| `channel` | 0 | Default channel |

The `to` field is the hex node ID from the QR URL's `n` parameter,
parsed as a uint32. Example: QR contains `n=4a312aaa`, set
`meshPacket.to = 0x4a312aaa` (decimal `1244736170`).

### BLE device name

The board advertises over BLE as `<short_name>_<last_2_mac_bytes>`.
Example: short name "007", MAC ending in 61:84 → BLE name
`007_6184`. The `long_name` ("Mezulla 007") is NOT used in the
BLE advertisement — only in the Meshtastic protocol after
connection.

To find the board via BLE scan, match on:
- Service UUID: `6ba1b218-15a8-461f-9fa8-5dcae273eafd`
- Or device name pattern matching the short name from the QR

### Claim-ownership command (0x01)

| Field | Value |
|---|---|
| Command byte | `0x01` |
| Direction | Phone → Board |

Payload layout (raw bytes in `decoded.payload`):
```
[0x01]                         — command byte (CLAIM)
[token_length: 1 byte]         — length of the token string (8)
[token: token_length bytes]    — the pairing token from the QR URL
[owner_id: remaining bytes]    — stable phone-side identifier (e.g. UUID)
```

Response (in `decoded.payload` of the reply MeshPacket):
```
[status: 1 byte]               — 0x00 = OK, 0x01 = token mismatch, 0x02 = already claimed
[owner_id: remaining bytes]    — the stored owner (on OK)
```

### Ownership query command (0x02)

| Field | Value |
|---|---|
| Command byte | `0x02` |
| Direction | Phone → Board |

Payload: `[0x02]` (just the command byte).

Response:
```
[status: 1 byte]               — 0x00 = OK
[owner_id: remaining bytes]    — the stored owner (empty if unclaimed)
```

Allows the phone to detect Mezulla firmware vs stock Meshtastic.
Stock boards silently ignore `PRIVATE_APP` packets — no response
within 2 seconds means stock firmware.

### Release-ownership command (0x03)

| Field | Value |
|---|---|
| Command byte | `0x03` |
| Direction | Phone → Board (owner only) |

Payload: `[0x03][owner_id bytes]` — sender must prove they are the
current owner.

Response:
```
[status: 1 byte]               — 0x00 = OK, 0x03 = not owner
```

On success, board clears ownership and reboots into QR mode.

### BLE configuration notes

- **NO_PIN when unclaimed.** The firmware sets `bluetooth.mode =
  NO_PIN` while the board is unclaimed and showing the QR. The QR
  token (random, per-boot) IS the authentication — a BLE PIN on
  top is redundant. With FIXED_PIN mode, Android GATT silently
  drops writes that happen before BLE bonding completes, so the
  claim packet never reaches the firmware's onWrite callback.
  After claiming, the board reboots and picks up the user's saved
  BLE mode from config.
- **BLE advertising timeout.** Stock Meshtastic default is 60
  seconds (`power.wait_bluetooth_secs`). The firmware does NOT
  currently override this. If pairing takes longer than 60 seconds
  from boot, the board stops advertising. This is a known
  limitation to address separately.
- **Single BLE client.** Meshtastic allows only ONE BLE connection
  at a time. If the official Meshtastic app is connected, Tern
  cannot connect. The Meshtastic app must be force-stopped or
  uninstalled on the pilot's phone.

## Epic 02 extensions (future, not yet designed)

### Traffic telemetry message type

A new port number (to be allocated via upstream Meshtastic PR, story
2.5) for aviation traffic contacts decoded from FANET/FLARM/ADS-L.
Payload will include: position, altitude, speed, heading, climb
rate, aircraft type, protocol source. Wire format TBD — designed
during upstream engagement.

### Gap-scan control (fork only)

Potential phone → board command to configure gap-scan parameters
(duty cycle, target frequencies, enable/disable). Not designed yet.
Lives on the Mezulla fork, not upstream.

## Versioning

The contract is versioned by stage. Stage 1 is pure stock
Meshtastic — no version negotiation needed. Stage 2 adds
Mezulla-specific packets on `PRIVATE_APP`; the phone detects
Mezulla firmware by the presence of the ownership query response.
Stock Meshtastic boards silently ignore `PRIVATE_APP` packets they
don't understand.
