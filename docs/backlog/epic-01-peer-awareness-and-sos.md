# Epic 01: Pilots in the same area see each other and signal for help without cell service

> This file describes the **end goal** — what "done" looks like once the
> whole feature exists. What we're *actually building right now* toward
> this end goal lives in [current-focus.md](current-focus.md). When this
> end goal is reached, that file goes away.

## Why this matters

Paragliding pilots fly alone, often in places with no cell signal. Two
unknowns make that scarier than it needs to be: "Where are my friends?"
and "If something goes wrong, can I reach anyone?" This epic shrinks both
unknowns. Tern phones near each other talk over LoRa via a paired LilyGo
board — peers appear on the map, and any pilot can fire a one-button SOS
that the others see immediately.

Tern keeps working without LoRa hardware; it just doesn't gain these
features. The LilyGo board is an addition, not a requirement.

## What done looks like

- When two or more Tern pilots are flying within LoRa range and each has
  a paired board, they see each other's current positions on the map
  without any cell service.
- Peer markers show a "last seen" age so the pilot can tell at a glance
  whether the position is fresh or stale.
- Pressing one button broadcasts an SOS over LoRa; everyone in range
  sees a high-priority alert with the sender's last known position.
- The LilyGo board's OLED shows link state, peer count, last beacon age,
  and battery — readable at arm's length without waking the phone.
- The OLED visibly confirms when an SOS goes out or comes in.
- If no board is paired, or the board disconnects mid-flight, the Tern
  phone app keeps working normally and shows a discreet "no LoRa"
  indicator. No error modals, no blocking states.

## Out of scope (for this epic; may become later epics)

- Text messaging between pilots — position + SOS only for now.
- Hardware-only operation. The board never operates without a paired
  phone in this epic. A hardware-only SOS button on the board is also
  out of scope here.
- Ground crew / family / friend live tracking link.
- Custom radio hardware or a custom protocol — see open questions.

## Stories

### Story 1.1: Phone discovers and pairs with a LilyGo board
Status: done (2026-05-27, verified end-to-end on real hardware)

Current state: QR pairing works. Phone camera → tern:// deep link →
BLE scan → GATT connect → MTU 517 → claim on PRIVATE_APP (port 256) →
ownership persisted → BLE mode switches to FIXED_PIN. Automated test
decodes the QR from the board's OLED screen dump. Remaining: auto-
reconnect lifecycle (board disconnect/reconnect mid-flight).

What shipped:
- QR scan with phone camera opens Tern, claims board over BLE.
- Pairing persists across app and board reboots.
- Settings shows paired board name + "Forget Board" button.
- Automated test decodes QR from OLED screen dump — no human needed.
- BLE mode: NO_PIN when unclaimed, FIXED_PIN when claimed.

Remaining:
- Auto-reconnect when board drops and reappears mid-flight.
- Release packet (app "Forget Board" clears local state but doesn't
  tell the board yet).

### Story 1.2: My position is broadcast over LoRa when a board is paired
Status: done (hardware-verified 2026-06) — see current-focus.md

All three prerequisites shipped: persistent BLE connection, the
ToRadio/FromRadio Meshtastic codec, and the GPS → Position → ToRadio send
path. Exercised end-to-end on real hardware by the cycle tests (the DUT's
mock GPS drives the board's transmit path).

Requires three pieces (now all done):
1. Persistent BLE connection (keep GATT open after pairing)
2. ToRadio/FromRadio codec (Meshtastic protobuf encode/decode in Kotlin)
3. GPS → Position protobuf → ToRadio write to board

No custom packet format needed — Meshtastic's POSITION_APP (portnum 3)
already defines lat/lon/alt/time. The board handles LoRa broadcast
automatically once it receives the position via BLE.

When a board is paired, Tern sends the pilot's GPS position to the board,
which transmits it over LoRa at a sane cadence. When no board is paired,
nothing happens — silently.

What done looks like:
- Position packets transmit at the chosen cadence on a real board.
- No transmission attempts when no board is paired (no errors, no log spam).
- The board doesn't crash or drain battery unreasonably when the phone is briefly idle.

### Story 1.3: Other pilots' positions appear on my map
Status: done (hardware-verified 2026-06) — exceeded original scope

Peer positions flow through the real wire path (FromRadio BLE read →
decode Position protobuf → PeerState → Redux → map), verified on real
hardware by three replayed flights — Aravis (4 pilots), Edith's Gap (2),
Bir Billing (3) — via the unified `MezullaPeerCycleTest` harness.

What done looks like (all met):
- Peer markers render correctly on a real device with peers on air.
- Stale ageing is visible at a glance.
- More than one peer at a time works.

Delivered beyond the original "marker" scope:
- **Full peer HUD** (`PeerLayer.renderMarkerBitmap`): callsign, staleness
  puck (green→yellow→orange→gray) + person glyph, heading/track arrow,
  relative altitude vs me, distance, view-mode metric (SAFETY/CLIMB/
  TACTICAL), STALE/LOST status, and zoom-based declutter — bound to the map
  via a data-driven `SymbolLayer`.
- **Off-screen buddy indicators** (`OffScreenPeerIndicators`): screen-edge
  chips (callsign + distance) pointing to buddies outside the map view —
  so a peer is never silently lost on a wide XC.

### Story 1.4: One-button SOS, broadcast and receive
Status: todo

The pilot fires SOS from a glove-friendly control. The board broadcasts
an SOS packet with the pilot's last known position. Other Tern phones
receiving it pop a high-priority alert.

What done looks like:
- Button is reachable with gloves on, with a confirm step to prevent
  accidental triggers.
- Receiving phones show the SOS source clearly.
- The SOS packet is more robust than position packets (e.g. repeated
  transmission until acknowledged).

### Story 1.5: OLED shows radio status at a glance
Status: todo

The board's OLED shows link state, peer count, last beacon age, and
battery — without needing the phone awake.

What done looks like:
- All four pieces of info are readable at arm's length in daylight.
- Updates without noticeable lag.

### Story 1.6: OLED confirms SOS sent or received
Status: todo

When the pilot fires SOS, the OLED shows "SOS SENT" distinctly. When the
board receives an SOS from a peer, OLED shows the source. The pilot knows
it happened even if the phone is unreadable or asleep.

What done looks like:
- Both states are visually distinct from normal status.
- They persist long enough to notice.
- Clears on acknowledgment from the phone.

### Story 1.7: Graceful behavior when no board, no peers, or disconnect
Status: todo

If no board is paired, the board disconnects mid-flight, or no peers are
in range, the app keeps working with no error modals. A small unobtrusive
indicator tells the pilot the state ("no LoRa" / "no peers"). Other Tern
features are untouched.

What done looks like:
- App shows no blocking error when the board goes away.
- Re-pairing happens automatically when the board comes back.
- The "no LoRa" / "no peers" state is glanceable but not visually noisy.

## Open questions

- ~~**Meshtastic or custom protocol?**~~ DECIDED: Meshtastic for now
  (off-the-shelf LilyGo T3 V1.6.1). Custom board and protocol later
  once the feature proves itself. Local Meshtastic fork if needed for
  QR pairing.
- ~~**Phone ↔ board transport.**~~ DECIDED: BLE only. TCP bridge
  killed — test bench uses real phone over WiFi adb, not emulator.
- **Beacon cadence vs. board battery life.** What rate balances peer
  freshness against a 2–5 hour flight on the board's battery?
- **Realistic LoRa range in paragliding.** Open air, high altitude,
  line-of-sight. Drives whether "see my whole buddy group" is realistic
  or we're really covering "see my neighbors."
- **SOS reliability.** Should the board retransmit until ACK? How many
  times? Trades airtime against confidence.

## Related

- `project_tern_safety_stack` — this delivers the LoRa layer of the
  layered safety stack.
- `project_tern_graceful_degradation` — the "no board" path is part of
  the feature, not an edge case.
- `project_tern_current_priority` — context for why this is the active focus.
- Branch name `mezulla` — Hittite intermediary deity; reflects the
  feature's role relaying messages between pilots.
