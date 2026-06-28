# MeshtasticConnection — design notes (WS2.1)

This is the rationale behind the
`com.ternparagliding.mezulla.connection.MeshtasticConnection` interface.
The KDoc on the interface itself restates the shape and contracts; this
doc explains *why* the shape is what it is.

Workstream context: [BACKLOG.md](../backlog/BACKLOG.md) (Epic 01), WS2.

## What the interface represents

One link from Tern to a paired Meshtastic LoRa node (e.g. a LilyGo T3
board). The interface is the seam between Tern's application code and
the three transports it might be talking to:

| Transport | Use | Status |
|---|---|---|
| `StubMeshtasticConnection` | Unit tests; lets the test inject events. | landed (WS2.1) |
| `SwarmSimulatedConnection` | IGC-driven swarm playback for convergence/replay testing. | landed |
| `TcpMeshtasticConnection` | TCP to a board on WiFi; also dev-time emulator bridge. | landed (WS4.5) |
| `BleConnection` | Real BLE to a flashed LilyGo board. | skeleton landed (WS4.3), untested on real hardware |

### Pairing protocol (PRIVATE_APP port 256)

Separate from the `MeshtasticConnection` event stream. The ownership
protocol rides on Meshtastic's `PRIVATE_APP` port for claim/query
packets between the Tern app and a Mezulla-firmware board.

| Component | What | Status |
|---|---|---|
| `MezullaPairingCodec` | Encode/decode claim + query packets, wrap in ToRadio protobuf | landed |
| `TernPairLink` | Parse `tern://p?n=<hex>&t=<hex>` deep link from QR scan | landed |
| `AndroidManifest.xml` | `tern://` scheme + BLE permissions registered | landed |
| Pairing orchestrator | Deep link → BLE scan → connect → claim → persist | next |

Wire format: [BACKLOG.md → Reference — QR pairing wire protocol](../backlog/BACKLOG.md#reference--qr-pairing-wire-protocol)

Everything above the interface — redux middleware, peer map overlays,
SOS UI — talks only to `MeshtasticConnection`. Nothing above the
interface should know which transport is underneath.

## Mapping to Meshtastic protobuf

The interface taxonomy mirrors Meshtastic's `PortNum` split so the wire
mapping is mechanical, not interpretive.

| Direction | Tern type | Meshtastic |
|---|---|---|
| In | `MeshEvent.PeerPositionUpdate` | `MeshPacket` on `POSITION_APP` (port 3) with `Position` payload |
| In | `MeshEvent.PeerTelemetry` | `MeshPacket` on `TELEMETRY_APP` (port 67) with `DeviceMetrics` |
| In | `MeshEvent.PeerAlert` | `MeshPacket` on `ALERT_APP` (port 11) |
| In | `MeshEvent.PeerIdentityKnown` | `MeshPacket` on `NODEINFO_APP` (port 4) with `User` payload |
| In | `MeshEvent.LinkStateChange` | Originates on the phone side (BLE notify), not a packet |
| Out | `sendOwnPosition` | `MeshPacket` on `POSITION_APP` |
| Out | `sendAlert` | `MeshPacket` on `ALERT_APP`, `Priority.ALERT` (110), `want_ack = true` |

`Position` on the wire uses `latitude_i` / `longitude_i` as `sfixed32`
scaled by 1e-7. We decode to doubles at the boundary so callers don't
keep redoing the scale.

We deliberately do **not** put a generic "raw MeshPacket" event on the
interface. Once we surface that, callers will reach for it and the
abstraction stops being one. If a future feature needs a port we
haven't surfaced (e.g. WAYPOINT_APP for shared waypoints), we add a
named `MeshEvent` variant for it, not a generic escape hatch.

## Decision: SOS path

**Use `ALERT_APP` (port 11) with `Priority.ALERT` (110) and
`want_ack = true`.**

Why:

- Meshtastic defines `ALERT_APP` explicitly as "used for critical
  alerts." That is the matching native path. Inventing a custom
  PortNum would force every receiving Tern node to recognise it and
  would break inter-op with stock Meshtastic clients listening on
  ALERT_APP.
- `Priority.ALERT` (110) sits just under ACK (120) in Meshtastic's
  outbound queue, so an SOS packet jumps ahead of position broadcasts
  on the airwaves — which is what we want.
- `want_ack = true` gives us per-hop ACKs to drive the
  `SendResult.Acked` / `SendResult.NoAck` distinction. The pilot needs
  to know whether the SOS actually left the board. `sendOwnPosition`
  deliberately does not have this — position is lossy by nature and
  one missed broadcast is fine.

What we still need to nail down (flagged for the user):

- **Retransmission policy.** `maxRetries = 3` is a placeholder. Real
  numbers want airtime measurement on a flashed board (WS4).
- **Inter-op with stock Meshtastic clients.** A pilot using a vanilla
  Meshtastic app and a LilyGo board *will* receive Tern-originated
  SOS packets as ALERT_APP messages. That is desirable (they see the
  alert), but the payload schema (if any) is open. Proposal: payload is
  a small protobuf with `sender_node_number`, `last_known_position` (the
  same `Position` shape), `timestamp`. Compatible with the open
  question in `epic-01` about SOS payload shape — left for WS2.2.

## Decision: peer identity surface

**`PeerIdentity` carries all three identifiers (`nodeNumber`, `hexId`,
optional `longName`/`shortName`) on every event.**

Why:

- `nodeNumber` is the only field guaranteed stable for the redux key.
  Names change; the node number does not (until the board is reset).
- `hexId` is the form pilots and bug reports use ("the `!a1b2c3d4`
  board"). Cheaper to format once at the boundary than at every log
  site.
- `longName` / `shortName` come from `NodeInfo.User` and may be absent
  when a position arrives before NodeInfo has been heard. The UI must
  fall back to `hexId` in that case — that's why those fields are
  nullable, not defaulted to empty string.

`MeshEvent.PeerIdentityKnown` exists specifically so the peer-list UI
can show a name as soon as NodeInfo arrives, even before the first
position fix — useful when a peer launches and is briefly visible by
name only.

Not done here: the pilot-name-to-callsign override layer. Some pilots
will want to override what they see on the map (e.g. "Antoine" instead
of the peer's chosen `long_name`). That is a UI-state concern, not a
connection-layer concern, and belongs in WS3.

## Decision: link state granularity

**Three states: `NEVER_PAIRED`, `DOWN`, `UP`.**

WS5 Phase 1 needs exactly two pilot-visible behaviors:

1. *No board has ever been paired* → silent. No "set up LoRa" prompt,
   no banner, nothing. This is `NEVER_PAIRED`.
2. *A board is paired but currently unreachable* → discreet "board
   off" reminder, auto-clears when the board comes back. This is
   `DOWN`.

`UP` is the normal-operation state.

We deliberately do **not** split `DOWN` into BLE sub-states (scanning,
connecting, connected-but-no-packets, etc.). The pilot can't do
anything useful with that detail mid-flight, and the auto-connect loop
in WS5.1.3 tries everything anyway. If we ever need finer detail for
diagnostics, it can be a side channel (debug-only log), not a UI-facing
state.

The transition `DOWN → UP` after a previous `DOWN` is what triggers
"board reconnected — clear the reminder" in WS5.1.4. The transition
`UP → DOWN` is what triggers "show the reminder." The transition
`NEVER_PAIRED → DOWN` happens when pairing completes (WS5.1.2).

## Decision: events as a single Flow

One `Flow<MeshEvent>` carries everything (peer events + link state)
because the redux middleware in WS2.4 is a single subscriber that needs
ordering between, for example, "link came UP" and "peer position
arrived." Splitting into two flows would force the middleware to merge
them and lose the ordering guarantee.

`MutableSharedFlow` is cold to late subscribers (no replay). A test
that needs to assert ordering must subscribe before injecting — which
the smoke test does, using Turbine's `.test {}` block.

## What this interface deliberately does not do

- **No protobuf encoding.** The wire format lives in the transport
  implementations (BLE / Simulator). The interface speaks Tern types.
- **No pairing.** Pairing is WS5 (Phase 1: settings-screen scan; Phase
  2: QR marriage). The interface assumes a board identity is already
  known (or null, for `NEVER_PAIRED`).
- **No retry policy for position.** Position is fire-and-forget. Only
  `sendAlert` retries.
- **No multi-board.** One paired board at a time, per the QR-marriage
  model (`project_tern_qr_pairing_model`).
- **No raw-packet escape hatch.** Each new use case adds a named event
  variant.

## Open questions (flagged for the user)

1. **SOS payload schema.** Decided to use ALERT_APP, but the bytes on
   the wire are still open. Proposed: protobuf with sender node number,
   last-known position (reuse `Position`), timestamp. Confirm before
   WS2.2 freezes the swarm-simulator wire format.
2. **SOS retransmission count and cadence.** `maxRetries = 3` is a
   placeholder. Real numbers want airtime measurement (WS4).
3. **Inter-op stance with stock Meshtastic clients.** Should Tern's
   SOS be readable by a vanilla Meshtastic Android app? If yes, the
   payload needs to be either plaintext or use a schema documented for
   third parties. If no, we can use an opaque Tern-specific encoding.
4. **Telemetry granularity.** Currently only `batteryPercent` is
   surfaced. Confirm that's enough for the buddy-flying scenario, or
   add channel utilization / voltage when we know we need them.
