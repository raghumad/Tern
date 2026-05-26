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
Status: todo

The phone app can detect a nearby LilyGo board, pair with it, and persist
the pairing across app restarts. If the board disconnects, the app keeps
working and quietly tries to reconnect in the background.

What done looks like:
- Pairing flow is reachable from settings and works on a real device.
- Pairing survives an app restart.
- Disconnect doesn't break or block any other part of the app.

### Story 1.2: My position is broadcast over LoRa when a board is paired
Status: todo

When a board is paired, Tern sends the pilot's GPS position to the board,
which transmits it over LoRa at a sane cadence. When no board is paired,
nothing happens — silently.

What done looks like:
- Position packets transmit at the chosen cadence on a real board.
- No transmission attempts when no board is paired (no errors, no log spam).
- The board doesn't crash or drain battery unreasonably when the phone is briefly idle.

### Story 1.3: Other pilots' positions appear on my map
Status: in-progress (emulator-verified, pending human test)

When another Tern pilot in LoRa range is broadcasting, their position
appears on my map. Each peer shows a "last seen Xs ago" indicator. Stale
markers fade visually but don't disappear abruptly.

What done looks like:
- Peer markers render correctly on a real device with at least 2 boards on air.
- Stale ageing is visible at a glance.
- More than one peer at a time works.

Current state (2026-05-25): Peer markers render as composite bitmap
GeoJSON features on native MapLibre SymbolLayer. Layout: callsign pill
above, colored circle with Nerd Font glyph, metric pills flanking
(age/altitude/climb/speed depending on view mode), warning pill below
for stale/lost peers. Staleness drives circle color (green→yellow→orange→
gray) and opacity pulsing. Three view modes (safety/climb/tactical).
BDD convergence test passes with 4-pilot Aravis XC flight simulation.
Standalone bitmap visual test suite covers staleness states, view modes,
and edge cases. NOT verified on a real device yet.

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
- ~~**Phone ↔ board transport.**~~ DECIDED: BLE for production, TCP
  for emulator dev workflow (emulator can't pass through Bluetooth).
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
