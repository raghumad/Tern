# Current focus

> The previous focus — **the Aravis replay milestone** — is **ACHIEVED**
> (2026-06). Its full text is preserved in
> [archive/current-focus-aravis-replay-achieved.md](../archive/current-focus-aravis-replay-achieved.md).
> This file now points at the next focus.

## Milestone achieved: peers on the map, on real hardware

The end-to-end peer-awareness path works and is verified on real hardware
(LilyGo T-LoRa board + Power Armor 14 Pro), across three real flights:

| Scenario | Pilots | Gradle task | Status |
|----------|--------|-------------|--------|
| Aravis (France) | 4 | `aravisCycleTest` | ✅ passing |
| Edith's Gap (USA) | 2 | `edithsGapCycleTest` | ✅ passing |
| Bir Billing (Himalayas) | 3 | `birBillingCycleTest` | ✅ passing |

What that proves end-to-end: GPS → phone → BLE → board → (simulated LoRa
receive) → BLE → phone → map. Delivered along the way:

- **Persistent, self-healing BLE link** with auto-reconnect (PR #20) and a
  reliability suite (T2 reconnect-after-reboot, T4 MTU 517, T6 heartbeat,
  T7 link badge, F5 PHY 2M).
- **Meshtastic ToRadio/FromRadio codec**, position send/receive, mock-GPS
  from IGC, `VirtualPeerInjector`, `MEZULLA_TEST_BUILD` radio-kill.
- **Full peer HUD** (replaced the green dot): callsign, staleness-coloured
  puck, heading/track arrow, relative altitude vs me, distance, view-mode
  metric (SAFETY/CLIMB/TACTICAL), STALE/LOST status, zoom declutter.
- **Off-screen buddy indicators** — screen-edge chips (name + distance)
  for buddies outside the map view; essential on wide XC.
- **Unified test harness** — one `MezullaPeerCycleTest` base drives the
  whole cycle; the three scenarios are thin subclasses differing only by
  IGC files.

## Next focus: finish Epic 01 (peer awareness + SOS)

Working toward: [Epic 01](epic-01-peer-awareness-and-sos.md). Peer
visibility (Stories 1.1–1.3) is done; what remains is SOS, the board's
OLED status, and graceful degradation.

| # | Story | What | Why now |
|---|-------|------|---------|
| 1 | **1.4 SOS alert UI** | One-button SOS broadcast + high-priority banner on receiving pilots (sender callsign + last known position, glove-friendly dismiss). | Safety-critical; the `ActiveAlert` model + dismiss action already exist. |
| 2 | **1.7 graceful degradation** | No board / no peers / mid-flight disconnect → discreet "no LoRa" indicator, never an error modal or blocked UI. | Safety: the app must never get in the pilot's way. |
| 3 | **1.5–1.6 OLED status** | Board OLED shows link state, peer count, last-beacon age, battery; confirms SOS sent/received. | Glanceable at arm's length without waking the phone. (Firmware.) |

After Epic 01 closes, the queue is **Epic 02 (traffic awareness)**, then
Epic 03 (Spedmo) and Epic 04 (onboarding).

## Smaller follow-ups (not blocking)

- Overlay reactive-render **soak test** (see
  [overlay-infrastructure-audit.md](overlay-infrastructure-audit.md) S1) —
  guards against OOM on multi-hour flights.
- App-side **release packet** on "Forget Board" (firmware handler exists;
  the app doesn't transmit it yet).
- Peer-state reset at scenario start (stale identity-only peers linger in
  test runs — cosmetic).
