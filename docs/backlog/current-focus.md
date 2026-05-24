# Current focus: pass the buddy-flying BDD test

Working toward: [Epic 01](epic-01-peer-awareness-and-sos.md)

## The convergence test (north star)

A multi-pilot, flight-grade BDD scenario that loads real IGC flight logs
(e.g. a team XC flight from XContest), runs them through a swarm
simulator with a configurable LoRa propagation model, and validates
that Tern — as one of those pilots, in the emulator — actually behaves
correctly. With screenshots and logs at every step.

In sketch form, the test reads like this:

```
given the swarm from xcontest flight tonio24/25.04.2026
  with pilot "tonio24" as the device under test
  and pilots "antoine", "guillaume", "luc" as virtual peers
  and a LoRa propagation model with a 15 km clear-air range

when the simulation runs from launch to landing

then the device under test sees all three peers on the map within
     the first 60 seconds after launch
 and at no point do all three peers go stale simultaneously while
     in line of sight
 and when "luc" flew behind the ridge at 12:34, his marker faded
     within 30 seconds
 and when "luc" emerged at 12:39, his marker recovered within 10
     seconds
 and if "antoine" had triggered an SOS at 13:11, "tonio24" would
     have seen it on the map within 5 seconds
```

This test passing is the milestone that proves mezulla actually works
when pilots actually fly together — not "works in a unit test."

## Why a convergence test, not a checklist

The previous AI built tests with names like
`pilot_flies_bir_billing_himalayan_odyssey` that hardcoded waypoints,
zoomed the map, and asserted UI text appears. The pilot never moved.
Nothing flew. The framework forced screenshots but the screenshots
were of static UI, not flying. The convergence test closes that hole
by making the simulation faithful to real flight — there's nowhere
for a "passing" test to mean something other than "this works."

See: [[project-tern-test-infrastructure-purpose]].

## Honest starting state

- **Exists and worth reusing:** `BddTest` framework with screenshot
  capture per step; `ScreenshotHelper`, `VisualValidator`,
  `MapVisualTest`, `TestCacheInjector`, `givenAppIsLaunchedOnMap`.
- **Already landed on `mezulla`:** IGC parser (WS1.1),
  `MeshtasticConnection` interface + stub (WS2.1), automated
  Meshtastic flash script verified on real hardware (WS4.2).
- **Real IGC test data is in place:** four real flights from a
  same-day team XC out of the Aravis (FR), 2026-04-25:
  `flights/fr/2026-04-25-aravis-team-{tonio24,cbe,cor,lma}.igc`. The
  first three launched together within 20 seconds from the same site;
  `lma` launched 22 min later from ~30 km NE. The hand-crafted
  `fixtures/synthetic-short-flight.igc` stays for parser edge-case
  tests.
- **Doesn't exist yet:** multi-pilot playback engine, scenario
  manifest layer, propagation model, swarm simulator, peer concept in
  Tern's redux state, peer markers on the map, SOS alert UI, real
  `BleConnection` / `TcpConnection`, any pairing UX.
- **Lying:** the existing competition-named tests claim flight
  scenarios but only test UI. They are not evidence the app works.

## Workstreams

Four parallel streams. WS1, WS2, WS3 all gate the convergence test.
WS4 (real hardware) is parallel — it validates the abstraction
against real BLE but is not required for the convergence test to
pass.

### WS1 — Swarm simulator (test infrastructure)

Builds the harness the convergence test runs in.

Milestones, in build order:

- **1.1** IGC parser. Reads the FAI IGC text format into a list of
  timestamped `(lat, lon, altitude)` fixes. Pure logic; unit-tested
  on real IGC files.
- **1.2** Multi-pilot time-aligned playback engine. Loads N IGCs,
  exposes "at time T, what is each pilot's position?" with linear
  interpolation between fixes.
- **1.3** LoRa propagation model interface, with at least
  `DistanceOnlyPropagation(maxRangeMeters)` as the first cut. Hooks
  for adding `LineOfSightPropagation(demData)` later, without
  refactoring callers.
- **1.4** `SwarmSimulator`: combines playback + propagation. For each
  virtual pilot at each tick, generates a Meshtastic-shaped position
  packet, asks the propagation model who receives it, delivers to
  subscribers via an event stream.
- **1.5** BDD vocabulary extending the existing `BddTest`:
  `given the swarm from <fixture>`, `with pilot <X> as the device
  under test`, `with pilots <Y, Z> as virtual peers`, `with a LoRa
  propagation model <…>`, `when the simulation runs from launch to
  landing`.
- **1.6** Real IGC fixtures + scenario manifest layout under
  `app/src/test/resources/igc/`. Real flights are already in place
  for the 2026-04-25 Aravis team scenario (4 pilots: tonio24, cbe,
  cor, lma). What's still pending: a scenario manifest format
  (Kotlin data class for now; can migrate to YAML later) that names
  pilots, points to their IGCs, and is referenced by BDD scenarios.
  See `app/src/test/resources/igc/README.md` for the layout.

**Definition of done (WS1):** A smoke test loads the committed IGC
bundle and asserts the simulator produces position packets at the
expected times for each virtual pilot. Verified by reading the test
log; failure prints a diff between expected and actual packet streams.

### WS2 — `MeshtasticConnection` abstraction (Tern app)

The interface both the simulator and real BLE plug into.

- **2.1** Define `MeshtasticConnection` interface based on Meshtastic
  protobuf schema. Events out: peer position, peer status / heartbeat,
  alert (SOS), link state. Commands in: send our position, send
  alert.
- **2.2** Implement `SwarmSimulatedConnection : MeshtasticConnection`
  that subscribes to a `SwarmSimulator` and surfaces its events
  through the interface.
- **2.3** Define a redux state slice for known peers
  (id, last-known-position, last-seen-timestamp) and active alerts.
- **2.4** Middleware that consumes `MeshtasticConnection` events and
  dispatches the right redux actions.

**Definition of done (WS2):** A test demonstrates a synthetic peer
position event flows through `SwarmSimulatedConnection` → middleware
→ redux state, observable in the store. Plus a test for the SOS
event path.

### WS3 — Peer rendering and alerts (Tern UI)

The pilot-visible surface that the convergence test screenshots
against.

- **3.1** New map overlay for peer pilots, extending the existing
  overlay system. One marker per known peer.
- **3.2** Marker design: peer name / callsign, "last seen Xs ago"
  indicator, fades visually as data goes stale, doesn't disappear
  abruptly.
- **3.3** SOS alert UI: high-priority surface that shows the sender
  and their last known position. Dismissable; doesn't auto-clear.
- **3.4** "No board" / "board off" discreet indicator. No error
  modal. Not surfaced at all when no board has ever been paired.

**Definition of done (WS3):** A BDD scenario in the swarm framework
asserts: virtual peer broadcasts a position → marker appears on map
(screenshot evidence) → marker fades after N seconds of no updates
(screenshot evidence) → marker recovers when updates resume
(screenshot evidence).

### WS4 — Real-hardware comms (parallel, doesn't gate convergence)

Validates the `MeshtasticConnection` abstraction works against real
BLE. Minimal scope: just "can we talk to a real board." Connection
lifecycle and pairing live in WS5.

- **4.1** Identify exact LilyGo T3 model. Match to the right
  Meshtastic firmware variant.
- **4.2** **Automated flash script** committed at
  `tern-android/scripts/flash-mezulla.sh` (or as a Gradle task).
  Takes `PORT` and `VARIANT` args, pins a Meshtastic version,
  downloads the right `.bin` from the GitHub release, runs
  `esptool.py write_flash`, verifies via `meshtastic --info`.
  Exits non-zero on any failure. No manual steps after the first
  invocation.
- **4.3** `BleConnection : MeshtasticConnection` skeleton — talks
  to a specific board's GATT (hardcoded MAC for now), surfaces
  Meshtastic events through the same interface as the simulator.
  No pairing, no lifecycle smarts — that's WS5.
- **4.4** Manual verification: board flashed, Tern connects to its
  MAC, byte channel round-trips. Capture serial log + a short video.

**Definition of done (WS4):** Tern can connect to a real flashed
LilyGo board and exchange Meshtastic packets, indistinguishable from
the simulator at the `MeshtasticConnection` interface level.

**Human test (required for done):** With the real `mezulla` board
plugged in and Tern installed on the user's physical Android phone,
the user performs a scripted byte-channel round-trip end-to-end and
captures evidence (logcat + a short video). Automated tests passing
in the emulator do **not** close WS4 by themselves — see
[[project-tern-human-tests]].

### WS4.5 — `TcpMeshtasticConnection` (dev convenience + future feature)

A second concrete implementation of `MeshtasticConnection` that talks
to a Meshtastic node over TCP, not BLE. Two reasons to exist:

- **Dev workflow today.** The Android emulator can't pass through the
  host's Bluetooth radio. Running Tern in the emulator and having it
  talk to a real mezulla on the same laptop requires either a
  serial-to-TCP bridge on the host (`socat`) or putting mezulla on
  the local WiFi so the board exposes its TCP API. Either way, the
  emulator-side code path is TCP, not BLE.
- **Real product feature.** A pilot who keeps the board on their home
  WiFi for charging can legitimately use the TCP path. The
  abstraction earns its keep twice.

Milestones:

- **4.5.1** Pick the dev-time bridging approach: socat-bridge of
  `/dev/ttyACM0` (no WiFi setup) vs configuring mezulla's WiFi to
  join the host's network. Pick the one that works first; document.
- **4.5.2** Implement `TcpMeshtasticConnection` against Meshtastic's
  TCP framing.
- **4.5.3** A BDD scenario or smoke test that runs Tern in the
  emulator, connects to the real mezulla over the chosen TCP path,
  and observes the board's own node info come through.

**Definition of done (WS4.5):** With Tern running in the emulator
and the user's real mezulla reachable via the chosen TCP transport,
Tern observes mezulla's `NodeInfo` and any position packets it
sends. Captured as a short screen recording.

### WS5 — Pairing and connection lifecycle (parallel)

Bridges WS4's raw comms with the pilot-facing "I never think about
pairing" experience. Split into two phases so we ship Phase 1
quickly and the polish UX comes later.

#### Phase 1 — Minimum viable pairing (now)

Enough pairing to actually use Tern with a real board in the field
while everything else gets built. Throwaway UX; replaced by Phase 2.

- **5.1.1** Settings screen: BLE scan for nearby Meshtastic boards,
  list them, pilot taps one to pair. Mark this UI explicitly as
  throwaway in code (`@Deprecated("Phase 1 pairing — replace with
  QR marriage flow in Phase 2")`).
- **5.1.2** Persist the chosen board's identifier in Tern.
- **5.1.3** Always-on auto-connect to the persisted board when it
  appears. No manual reconnect button.
- **5.1.4** Discreet "board off" notification when paired but
  absent. Auto-clears when the board comes back. Phrased as a
  reminder, not an error.
- **5.1.5** Graceful no-board behavior: when nothing is paired,
  the app surfaces no warning, no banner — silent.
- **5.1.6** `SwarmSimulatedConnection` extended to mimic BLE
  lifecycle events (board appears, board disappears) so all of
  5.1.3–5.1.5 is testable in the emulator.
- **5.1.7** BDD scenarios for the lifecycle flows in the emulator
  + manual real-hardware validation.

**Definition of done (WS5 Phase 1):** Pairing lifecycle BDD
scenarios pass in the emulator.

**Human test (required for done):** With the user's real Android
phone running the mezulla build and the real LilyGo board, the user
performs the pair-once flow, restarts the app, confirms the board
auto-connects, powers the board off and confirms the "board off"
notification appears, powers the board on and confirms auto-reconnect
clears the notification. Captured as a 60–90s screen recording. The
emulator BDD pass does **not** close Phase 1 by itself — see
[[project-tern-human-tests]].

#### Phase 2 — QR marriage (deferred until Phase 1 ships)

The pilot-facing polish UX. See
[[project-tern-qr-pairing-model]] for the full design intent.

**We work from a local fork of Meshtastic from day one.** The
upstream-vs-permanent-fork decision is deferred to release time
(per [[project-tern-hardware-roadmap-and-openness]]) — by then we
know what we actually changed and whether upstream will take it.

- **5.2.1** Pairing protocol design doc — `tern://` URL format, QR
  contents, claim-ownership packet on top of Meshtastic, board
  reset trigger (long-press boot button 5s), security properties.
- **5.2.2** Set up local Meshtastic build environment: clone,
  build for the target LilyGo variant, flash from source. Replaces
  the prebuilt `.bin` path from WS4.2 with our own build pipeline.
- **5.2.3** Board firmware modifications (on our local Meshtastic
  checkout): QR generation on ownerless boot, ownership state in
  flash, claim-ownership packet handler, reset path.
- **5.2.4** Tern: register `tern://` custom-scheme deep link
  handler. Chosen over HTTPS App Link because pairing must work
  offline at remote launch sites (see
  [[project-tern-offline-first]]).
- **5.2.5** Pairing flow logic in Tern: receive deep link →
  connect → send claim-ownership → persist marriage locally.
- **5.2.6** Replace the Phase 1 settings pair UI with the QR
  scan flow. Settings now only *shows* the currently-paired board
  and offers "forget."
- **5.2.7** BDD scenarios for the QR pairing flow in the emulator
  + manual real-hardware validation.

**Definition of done (WS5 Phase 2):** QR pairing BDD scenarios pass
in the emulator.

**Human test (required for done):** The user takes a freshly-reset
real board, scans the on-OLED QR with the phone's native camera, and
the marriage completes end-to-end. Repeat the test with a second
"pilot" (different phone or freshly-reset Tern install) scanning the
same board to confirm the second marriage replaces the first.
Captured as a screen recording of each phone plus a photo of the
board through the flow. The emulator BDD pass does **not** close
Phase 2 by itself — see [[project-tern-human-tests]].

**Risk guard:** don't cut a public release until Phase 2 lands.
Phase 1 UI is internal/dev use only.

## Convergence

The buddy-flying BDD test passes when **WS1 + WS2 + WS3** each meet
their done criteria. **WS4 + WS5 Phase 1** prove the same
abstraction works against real hardware. **WS5 Phase 2** layers on
the polish UX. At the point all of those are done **and the human
tests in WS4 / WS5 have been performed and captured as evidence**,
this focus area is done and gets replaced.

The buddy-flying BDD test passing in the emulator is necessary but
not sufficient. See [[project-tern-human-tests]] — every workstream
that touches real hardware has a human test step that closes it,
separate from the automated tests.

## Known deviations from real LoRa (deliberate, deferred)

The simulator is a **development-velocity tool first**, fidelity tool
second (see [[project-tern-simulator-purpose]]). We build the happy
path assuming the radio layer just works; chaos modeling is deferred.

These deviations are known and intentional in this focus area:

- **No mesh repeats.** Real Meshtastic hops packets through
  intermediate nodes; `DistanceOnlyPropagation` only models direct
  point-to-point. Peers that are out of direct range will not appear
  in the simulator even if they would in real life via a relay.
- **No collisions / airtime / duty-cycle.** Every broadcast that
  passes the propagation check is delivered to every in-range pilot.
  Real LoRa shared-frequency collisions are not modeled.
- **No smart broadcast.** The simulator broadcasts position on a
  fixed periodic interval. Real Meshtastic adjusts cadence based on
  movement.
- **No real BLE transport.** Events flow directly into
  `PeerMiddleware`. BLE quirks (drops, GATT timeouts, MTU
  reassembly, latency spikes) are not exercised.
- **No GPS receiver noise.** IGC fixes are post-smoothed; real radios
  broadcast positions with chip-level jitter.
- **Single DUT per run.** One Tern instance plays one pilot; others
  are virtual. Symmetric N-pilot interaction would require running
  the scenario N times with each pilot as DUT.

These will become explicit "chaos monkey" upgrade workstreams later,
focused on hardening Tern against real-world conditions. They are
**not** prerequisites for the convergence test being meaningful —
real-world correctness comes from human tests (see
[[project-tern-human-tests]]), not from simulator realism.

## Order of attack

- **WS1** is the longest pole and has no dependencies — start there.
- **WS2** can begin in parallel as soon as the
  `MeshtasticConnection` interface shape is sketched (doesn't need
  the simulator working yet).
- **WS3** depends on WS2's redux slice.
- **WS4** can start any time once WS2's interface is defined.
- **WS5 Phase 1** depends on WS4 (real BLE) for hardware
  validation, but the in-emulator BDD scenarios can start as soon
  as WS2's lifecycle event types exist.
- **WS5 Phase 2** is deferred until Phase 1 is solid.

## Cleanup that comes along the way

The misleading competition-named tests (`BirBillingCompetitionTest`,
`ChamonixCompetitionTest`, `MonarcaCompetitionTest`,
`AviationRoutePlanningTest`) need an honest reckoning. Most of their
content is UI-only checks dressed up as flight scenarios. As parts
of WS3 land, decide for each: keep as a renamed honest UI test, fold
into a swarm scenario, or delete. Don't leave them claiming things
they don't test.

## Open questions to resolve along the way

- **Exact LilyGo board model** (blocks WS4 start, not WS1–3).
- **IGC fixture sourcing.** XContest's IGC files: what's the license
  for committing them? If unclear, get permission or use equivalents
  the user has rights to.
- **Peer identity in Meshtastic.** Meshtastic uses node IDs; how do
  we map those to pilot names / callsigns for display?
- **SOS in Meshtastic.** Use the alert/emergency channel or define a
  custom port? Decide before WS2's SOS path is finalized.
