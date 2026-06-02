# Current focus: the Aravis replay

> **ARCHIVED / ACHIEVED (2026-06).** This milestone is done — the Aravis
> replay (and Edith's Gap + Bir Billing) pass end-to-end on real hardware.
> Preserved for history. The live focus is `docs/backlog/current-focus.md`.

Working toward: [Epic 01](epic-01-peer-awareness-and-sos.md)

## The milestone

A video of a real paragliding flight — the 4-pilot Aravis XC from
2026-04-25 — replayed at 64x through Tern and a real Mezulla board.
You press play and watch Tonio's flight unfold: three buddies appear
on the map, one disappears behind a ridge, reappears minutes later,
the board's tiny OLED shows peer count ticking. Phone screen and
board OLED side by side in one video.

This is the video you show friends to explain what we're building.
When this BDD test passes and produces that video, we celebrate.

## The BDD scenario

```gherkin
scenario: Aravis team XC replay — golden path (15 km range)
  given the Aravis IGC bundle (tonio24, antoine, guillaume, luc)
    and Tonio's phone (emulator) is paired with a real Mezulla board
    and the board is running test firmware (radio silent)
    and a LoRa propagation model with 15 km clear-air range
    and screen recording is active on the emulator
    and OLED screendumps are captured every 5 seconds from the board

  when the replay runs at 64x speed
    feeding Tonio's GPS to the phone as mock location
    and feeding Antoine, Guillaume, Luc's positions to the board
        as incoming LoRa packets (injected via BLE ToRadio)

  then within 60s of launch, all three peers appear on Tonio's map
   and the board's OLED shows "3 peers"
   and when Luc flies behind the ridge at 12:34, his marker fades
       within 30 seconds
   and when Luc emerges at 12:39, his marker recovers within 10
       seconds
   and throughout the flight, the phone screen recording shows
       peer markers moving smoothly
   and the final output is a side-by-side video:
       left = phone map, right = stitched OLED screendumps

scenario: Aravis team XC replay — chaos (random 3–15 km range)
  given the Aravis IGC bundle (tonio24, antoine, guillaume, luc)
    and Tonio's phone (emulator) is paired with a real Mezulla board
    and the board is running test firmware (radio silent)
    and a LoRa propagation model with random range 3–15 km
    and screen recording is active on the emulator
    and OLED screendumps are captured every 5 seconds from the board

  when the replay runs at 64x speed

  then peers appear and disappear as they cross the random range
       boundary — no crashes, no frozen ghosts
   and stale markers fade within 30 seconds of losing contact
   and markers recover within 10 seconds of regaining contact
   and the app never shows an error dialog or blocks the UI
   and the final output is a side-by-side video
```

The golden path proves the feature works. The chaos path proves it
handles real-world LoRa flakiness — peers flickering at range
boundaries, staleness timers firing, recovery after dropout. Both
produce a video.

## What this proves

- The full data path works: GPS → phone → BLE → board → (simulated
  LoRa receive) → BLE → phone → map rendering
- Meshtastic protobuf encode/decode is correct (ToRadio/FromRadio)
- Persistent BLE connection stays alive for an entire flight
- Peer staleness, appearance, disappearance all work with real timing
- The board's OLED shows useful information during flight
- The video is evidence a human can evaluate at a glance

## What needs to exist for this test to run

### 1. Persistent BLE connection
Keep GATT open after pairing. Subscribe to FromNum notifications.
Read FromRadio for incoming packets. Write ToRadio for outgoing.
Implement `BleConnection : MeshtasticConnection`.

### 2. ToRadio/FromRadio codec
Encode/decode Meshtastic protobufs in Kotlin. Position (portnum 3),
NodeInfo (portnum 4). Unit-testable without hardware.

### 3. Position send path
Phone GPS → Meshtastic Position protobuf → ToRadio → BLE write.
For the test: mock location provider fed from Tonio's IGC at 64x.

### 4. Position receive path
FromRadio BLE read → decode Position → PeerState → Redux → map.
For the test: host injects peer IGC positions into the board as
fake LoRa-received packets via ToRadio with the peer's node ID.

### 5. Test build flag (MEZULLA_TEST_BUILD)
Build-time radio kill. Forces `lora.region = UNSET`. Test firmware
physically cannot transmit. Production firmware gets region from
phone GPS.

```ini
[env:tlora-v2-1-1_6-test]
extends = env:tlora-v2-1-1_6
build_flags = ${env:tlora-v2-1-1_6.build_flags} -DMEZULLA_TEST_BUILD
```

### 6. IGC replay engine at variable speed
The swarm simulator already replays IGC files. Needs a speed
multiplier (1x, 32x, 64x) and the ability to feed positions into
two sinks: mock GPS for Tonio, ToRadio packets for peers.

### 7. Video assembly
- Phone: emulator screen recording (already works via VideoHelper)
- Board: periodic OLED screendumps stitched into a filmstrip
- Final: side-by-side composition (ffmpeg)

### 8. Auto-region from GPS
On BLE connect, phone pushes correct LoRa region to board. Not
needed for the test (test build forces UNSET), but needed for
production. Implement alongside persistent BLE connection since
it's a natural on-connect action.

## Build order

| Step | Deliverable | Test |
|------|------------|------|
| 1 | ToRadio/FromRadio codec | Unit tests: encode Position, decode Position, round-trip |
| 2 | Persistent BLE connection | Instrumented test: connect, stay connected 60s, exchange packets |
| 3 | Position send | Instrumented test: phone GPS appears in board's serial log |
| 4 | Position receive | Instrumented test: injected peer position appears in Redux |
| 5 | MEZULLA_TEST_BUILD | Build both environments, verify radio off in test build |
| 6 | Mock GPS from IGC | Unit test: IGC at 64x produces correct timestamps and positions |
| 7 | Aravis replay BDD | The scenario above — produces the video |

Each step has its own test. Step 7 is the convergence — all prior
steps must work for it to pass. The video is the artifact.

## What's done (prerequisites met)

| Area | Status |
|------|--------|
| Swarm simulator + IGC parser | Done |
| Peer marker rendering | Done (emulator-verified) |
| QR pairing (phone + board) | Done (hardware-verified) |
| OLED screen dump + decode | Done |
| On-demand screendump | Done |
| BLE scan + connect + claim | Done |
| Test bench (phone WiFi adb + board USB) | Done |

## After the Aravis replay

Once the video exists:

| Priority | Item | Why |
|----------|------|-----|
| 1 | SOS alert UI (Story 1.4) | Safety-critical — a pilot's life |
| 2 | Overlay reactive rendering (S1) | Prevents OOM crash on long flights |
| 3 | OLED status screens (Stories 1.5–1.6) | Peer count, battery, link state |
| 4 | Auto-reconnect lifecycle | Handle BLE drops mid-flight |
| 5 | Graceful degradation audit (Story 1.7) | Silent failure paths |
| 6 | Epic 02: Traffic awareness | FANET/FLARM/ADS-L |
