# Current focus: Epic 01 — buddy-flying with real hardware

Working toward: [Epic 01](epic-01-peer-awareness-and-sos.md)

## Where we are (2026-05-27)

QR pairing works end-to-end on real hardware. A pilot scans the
board's QR with a phone camera, the app claims ownership over BLE,
the board persists it, and after reboot the board remembers. The
test infrastructure can decode the OLED QR programmatically from
a serial screen dump — no human in the loop.

The convergence test (swarm simulator → peer markers on map) passes
in the emulator. Peer markers render as composite bitmap GeoJSON
on native MapLibre SymbolLayer with staleness colors and opacity
pulsing. SOS alert UI is the next safety-critical piece.

## What's done

| Area | Status | Evidence |
|------|--------|----------|
| Swarm simulator (WS1) | Done | IGC → playback → propagation → packets |
| MeshtasticConnection abstraction (WS2) | Done (simulator path) | SwarmSimulatedConnection → PeerMiddleware → Redux |
| Peer markers on map (WS3.1–3.2) | Done (emulator) | Convergence BDD passes, screenshot evidence |
| QR pairing firmware (WS-F2) | Done | Claim/query/release on PRIVATE_APP port 256 |
| QR pairing app (WS5 Phase 2) | Done | Deep link → BLE scan → connect → claim → persist |
| BLE mode transitions | Done | NO_PIN unclaimed → FIXED_PIN claimed → NO_PIN released |
| OLED screen dump | Done | Programmatic QR decode from SSD1306 buffer for BDD |
| On-demand screendump | Done | Query command triggers dump, like QEMU's screendump |
| Test bench | Done | Ulefone (WiFi adb) + board (USB serial), fully automated |

## What's next

### Now (safety-critical or gates everything)

1. **SOS alert UI (Story 1.4)** — one-button SOS, high-priority
   receive alert. Safety-critical feature. Needs protocol design
   (use Meshtastic alert channel or custom port?), glove-friendly
   UI, confirmation step.

2. **Overlay reactive rendering (S1)** — overlays accumulate as
   the pilot flies; 11-hour XC will OOM. Fix: re-render from
   Hilbert cache on viewport change, discard out-of-view overlays.
   Prevents crash in flight.

3. **Position broadcast (Story 1.2)** — core mesh functionality.
   Phone GPS → board → LoRa. Without this, peers can't see each
   other. Cadence vs battery tradeoff to decide.

### Soon (complete the Epic 01 MVP)

4. **Peer markers human test (Story 1.3)** — emulator-verified but
   never tested with real boards and real peers. Requires 2 phones +
   2 boards or one board + simulator feeding the second phone.

5. **OLED status screens (Stories 1.5–1.6)** — link state, peer
   count, beacon age, battery on the board's display. SOS
   confirmation screen.

6. **Auto-reconnect lifecycle** — board disconnect/reconnect mid-
   flight. The pairing persists but the BLE connection doesn't
   auto-resume yet.

7. **Graceful degradation audit (Story 1.7)** — verify every
   feature path handles missing board/peers/GPS silently.

### Later (after Epic 01 ships)

8. **Epic 02: Traffic awareness** — FANET/FLARM/ADS-L. Blocked on
   Epic 01 completion.

9. **Known test failures** — 19 instrumented tests were failing as
   of 05-23. Many were deleted in the test scrub; remaining failures
   are route planning and weather UX (parked during LoRa focus).

10. **Architecture cleanup** — overlay code quality (Q1–Q7),
    upstream Meshtastic PR prep (Stage 2.5), GeoPoint migration.

## Test infrastructure

Four verification layers, each for a specific purpose:

| Layer | What it proves | How |
|-------|---------------|-----|
| Emulator BDD | Code correctness | Swarm simulator, screenshots, Gherkin |
| Instrumented hardware test | BLE/GATT/protocol works | Real phone + real board, video evidence |
| OLED screen dump | Board display is correct | Serial hex → PIL image → pyzbar QR decode |
| Human test | Pilot can actually use it | Real hardware, real conditions, gloves, sunlight |

A feature is never done until its human test passes.

## Open questions

- **SOS protocol:** Meshtastic alert channel or custom PRIVATE_APP
  sub-command? Alert channel is upstream-compatible but may not
  support the retry semantics we need.
- **Beacon cadence:** What rate balances peer freshness against
  board battery life on a 2–5 hour flight?
- **Upstream PR timing:** When to generalize Mezulla → generic
  device pairing and submit to Meshtastic upstream?
