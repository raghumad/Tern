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
1. Generate a pairing token (random 16 bytes, hex-encoded).
2. Encode `tern://pair?node=<hex_node_id>&token=<pairing_token>`
   as a QR code.
3. Display QR on the OLED.
4. Accept BLE connections from any phone.

On receiving a claim-ownership packet (see below):
1. Verify the token matches the one displayed on the QR.
2. Store the claiming phone's identity in flash as the owner.
3. Clear the QR from the OLED.
4. From now on, only accept connections from the stored owner
   (or from any phone if no owner is set).

On long-press of boot button (5 seconds):
1. Clear owner from flash.
2. Generate new pairing token.
3. Display new QR on the OLED.

### Claim-ownership packet

| Field | Value |
|---|---|
| Port | `PRIVATE_APP` (256) |
| Payload type | Mezulla-specific protobuf (not registered upstream) |
| Direction | Phone → Board |

Payload fields:
- `pairing_token`: bytes — must match the QR-displayed token
- `owner_id`: string — phone-side identifier for the claiming pilot

Response: board sends ACK via `ROUTING_APP` if claim succeeds,
NAK if token mismatch.

### Ownership query packet

| Field | Value |
|---|---|
| Port | `PRIVATE_APP` (256) |
| Direction | Phone → Board |

Allows the phone to ask "who owns this board?" without needing to
see the QR. Response is a Mezulla-specific protobuf with `owner_id`
(or empty if unclaimed).

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
