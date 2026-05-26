# Epic 02: Pilots see nearby aircraft and are seen by them

Status: todo
Priority: after Epic 01 MVP
Depends on: Epic 01 WS5 Phase 2 (local Meshtastic build from source)

## Why this matters

A paraglider sharing a thermal with a glider, or flying near a
helicopter doing mountain rescue, has one critical unknown: "Is
anything about to hit me?" Today, only pilots who buy proprietary
FLARM hardware get collision awareness. Everyone else is invisible
to each other.

This epic adds aviation traffic awareness in two layers: clean
upstream contributions to Meshtastic, and Mezulla-specific firmware
on our fork for the harder radio work.

## Three layers: upstream Meshtastic, Mezulla fork, Tern app

**Upstream Meshtastic PRs** — general-purpose, non-controversial
improvements that benefit any Meshtastic user. Traffic telemetry
message type (new protobuf + port number), broadcast on
FANET/FLARM/ADS-L (brief retune, transmit, retune back),
configurable aircraft type, airborne detection gate. No paragliding
assumptions. Submitted with automated bench tests proving mesh
performance is not degraded. Benefits drone operators, glider clubs,
balloon pilots — anyone with a Meshtastic device who wants aviation
visibility.

**Mezulla fork** — gap-scan receive logic. This is the risky part
that keeps the radio off the Meshtastic frequency for longer
windows to listen for traffic. It lives on our Meshtastic fork,
not upstream. We bench-test it for our own go/no-go. If the data
looks good, we can propose it upstream later as a separate PR. If
it doesn't, we go dual radio on v2 and upstream is unaffected.

**Tern app** (Phase A) — paragliding-specific phone layer. Renders
traffic on the map, computes CPA tuned for paraglider speeds, fires
audio alerts. This is Mezulla-specific code, not upstream.

## Dependency on Mezulla (Epic 01)

All firmware stories require a working local Meshtastic build
environment (Epic 01, WS5 story 5.2.2) and familiarity with the
radio stack from real hardware work (WS4).

- **Phase A** stories run on the Tern phone app. Testable against
  the swarm simulator with synthetic traffic data. Can start as
  soon as Epic 01's `MeshtasticConnection` abstraction exists.
- **Phase B** stories modify Meshtastic firmware. Blocked on the
  local build pipeline and real hardware.

## What done looks like

- A pilot flying with Mezulla is visible on every Skytraxx, XCtracer,
  Oudie, and FLARM device in range — without buying any of those.
- The pilot sees other FANET/FLARM/ADS-L aircraft on the Tern map.
- When an aircraft is converging (CPA below threshold), the pilot
  gets an audio alert from Mezulla's speaker — no need to look at
  anything.
- A hiker with a plain Meshtastic device on the ground relays
  traffic into the mesh, extending coverage for airborne pilots
  behind ridges.
- A ground node never accidentally broadcasts itself as an aircraft.
- Broadcast + traffic telemetry message type are merged upstream
  in Meshtastic with bench test data proving mesh is not degraded.
- Gap-scan receive works on the Mezulla fork with bench data
  showing acceptable mesh impact (or dual radio is the v2 path).

## Out of scope

- ADS-B 1090 MHz reception (needs a separate receiver IC — future
  hardware, not this epic).
- Full FLARM-style trajectory prediction with wind modeling.
  Simple CPA (dot product on position + velocity vectors) is
  sufficient and honest.
- Dual radio support. That's v2 Mezulla hardware (custom board with
  a dedicated SX1276 for traffic). v1 (LilyGo T-LoRa V2.1, single radio)
  uses gap-scanning. If gap-scanning proves unacceptable (story
  2.10), dual radio gets promoted from future work.

---

## Phase A — Tern app (Mezulla-specific, simulator-testable)

These stories define how traffic data flows through Tern and reaches
the pilot. They use the same `MeshtasticConnection` abstraction as
peer awareness, so they're testable in the swarm simulator with
synthetic traffic injected as if it came from firmware.

### Story 2.1: Traffic data model in Tern

Status: todo
Depends on: Epic 01 WS2 (MeshtasticConnection + redux state)

Define the data structures for traffic contacts: position, altitude,
speed, climb rate, heading, aircraft type, protocol source
(FANET/FLARM/ADS-L), and staleness. Extend the redux state with a
traffic slice alongside the existing peer slice.

What done looks like:
- A `TrafficContact` data class exists with all fields.
- Redux state holds a bounded map of contacts, keyed by unique ID.
- Synthetic traffic events injected through
  `SwarmSimulatedConnection` flow into the store.
- Stale contacts are aged out automatically.

### Story 2.2: Traffic contacts on the map

Status: todo
Depends on: 2.1

Render traffic contacts as map markers, distinct from peer pilots.
Aircraft type drives the icon (paraglider, hang glider, glider,
helicopter, unknown). Markers age and fade like peer markers.

What done looks like:
- Synthetic traffic appears on the map in the emulator.
- Different aircraft types are visually distinguishable.
- Stale contacts fade; cleared contacts disappear.

### Story 2.3: CPA computation and collision alert

Status: todo
Depends on: 2.1

For each traffic contact, compute closest point of approach against
own position and velocity. If CPA distance < threshold and time to
CPA < horizon, fire an alert.

The math: relative position dot relative velocity, solve for time,
compute distance at that time. One dot product per contact per
update.

What done looks like:
- In the swarm simulator, a virtual FANET aircraft converging on the
  pilot triggers an alert.
- Alert includes direction and rough distance ("traffic, 500m, west").
- Non-converging traffic (parallel, diverging) does not alert.
- False-alarm rate on real flight data (IGC replays) is acceptable.

### Story 2.4: Audio traffic alert on Mezulla speaker

Status: todo
Depends on: 2.3

CPA alerts produce a distinctive audio tone on Mezulla's speaker.
The pilot hears "traffic nearby" without looking at anything.

What done looks like:
- Traffic alert tone is distinct from buddy-joined, SOS, and vario
  tones.
- Audible in a paragliding cockpit with wind noise.
- Does not fire for distant non-threatening traffic.

---

## Phase B — Meshtastic firmware (upstream PRs + Mezulla fork)

Split into two tracks:

**Upstream track** (stories 2.5–2.9): broadcast + traffic telemetry
message type. General-purpose, clean PRs to Meshtastic. Every story
includes automated bench tests with measurable results that ship
with the PR.

**Fork track** (stories 2.10–2.13): gap-scan receive logic on our
Meshtastic fork. Mezulla-specific. Bench-tested for our own go/no-go,
not for an upstream PR (yet). If the data is clean, we propose it
upstream later.

Ordered broadcast-first: being seen by other aircraft is immediate
safety value and simpler firmware work. Receive (gap-scanning) is
the harder technical risk, tackled second.

### Story 2.5: Upstream engagement

Status: todo
Depends on: Epic 01 WS5 Phase 2 (local Meshtastic build)

Open a Meshtastic RFC or discussion thread proposing the aviation
traffic awareness module. Initial scope for upstream: FANET/FLARM/ADS-L
broadcast (brief retune, transmit, retune back) and a new traffic
telemetry message type. Get maintainer feedback on module API
boundaries, protobuf/port number allocation, and acceptable mesh
impact before writing code. Gap-scan receive is not part of the
initial upstream proposal — that's Mezulla fork work.

What done looks like:
- Discussion thread exists on the Meshtastic GitHub.
- At least one maintainer has responded with feedback.
- We have agreement (or clear disagreement) on whether broadcast
  fits as a module or needs deeper integration.
- Port number and protobuf message type are allocated or agreed.

### Story 2.6: Bench test harness

Status: todo
Depends on: 2.5

Build the instrumented test infrastructure for measuring radio
behavior. Two Meshtastic devices on a bench: one device under test,
one reference node generating known mesh traffic at a fixed rate.

The harness measures:
- Baseline mesh packet delivery rate (no traffic module active).
- Radio retune latency (time off Meshtastic frequency per cycle).
- Mesh packet loss rate with the traffic module active.

When fork-track receive stories begin, the harness is extended to
also measure traffic packet capture rate (what percentage of
transmitted FANET/FLARM packets are successfully received).

What done looks like:
- Automated test script runs on the bench, produces a structured
  report (JSON or CSV) with the above metrics.
- Baseline measurement is captured and committed as the reference.
- The harness is reusable — every subsequent story runs it to
  measure its own impact.

Bench test results serve as the human test evidence for firmware
stories (equivalent to Epic 01's human test requirement for
hardware work).

### Story 2.7: Broadcast own position on FANET

Status: todo
Depends on: 2.6

When the device is airborne (GPS altitude change + speed above
configurable threshold), transmit a FANET Type 1 packet with
position, altitude, speed, climb rate, heading, and aircraft type.
Ground devices never broadcast.

Aircraft type is configurable (paraglider, hang glider, glider,
helicopter, balloon, drone, unknown). Mezulla defaults to
paraglider; that's configuration, not module code.

What done looks like:
- A Skytraxx or GXAirCom device on the bench receives and displays
  the device's position with correct aircraft type.
- A stationary device does not appear on any FANET receiver.
- Bench test report shows: retune latency per broadcast cycle,
  mesh packet loss rate during broadcast vs. baseline.

### Story 2.8: Broadcast own position on FLARM Legacy

Status: todo
Depends on: 2.7

Same as 2.7 but on FLARM Legacy protocol.

What done looks like:
- A real FLARM device (or SoftRF in Legacy RX mode) receives and
  displays the device's position.
- Same airborne gate — ground devices never broadcast.
- Bench test report with same metrics as 2.7.

### Story 2.9: Broadcast own position on ADS-L

Status: todo
Depends on: 2.7

Same as 2.7 but on ADS-L SRD860.

What done looks like:
- An ADS-L capable device receives and displays the position.
- Same airborne gate.
- Bench test report with same metrics.

### Story 2.10: Receive and decode FANET tracking packets (fork)

Status: todo
Depends on: 2.7
Track: Mezulla fork (not upstream — gap-scanning is too invasive
for an initial PR)

Gap-scan the FANET frequency (868.2 MHz EU / 920.8 MHz US) during
Meshtastic RX idle windows. Decode Type 1 (aircraft tracking) packets
into position, altitude, speed, climb rate, heading, aircraft type.

This is the gap-scan feasibility proof. The bench test answers the
key question: can we receive traffic without unacceptable mesh
degradation? If the numbers are clean, propose upstream later.

What done looks like:
- Decoded FANET packets appear in the Meshtastic log with correct
  position data.
- Bench test report shows: mesh packet loss rate with gap-scanning
  active, FANET packet capture rate, time per scan cycle.
- If mesh loss exceeds an acceptable threshold (compare against
  story 2.6's baseline), document the finding and flag dual radio
  as the path forward.

### Story 2.11: Receive and decode FLARM Legacy packets (fork)

Status: todo
Depends on: 2.10
Track: Mezulla fork

Gap-scan FLARM frequencies and decode reverse-engineered Legacy
protocol packets.

What done looks like:
- Decoded FLARM packets from a real FLARM device (or SoftRF in
  Legacy mode) appear in the Meshtastic log.
- Legal: source code includes the EU Software Directive
  interoperability citation in the file header.
- Bench test report with same metrics as 2.10.

### Story 2.12: Receive and decode ADS-L SRD860 packets (fork)

Status: todo
Depends on: 2.10
Track: Mezulla fork

Gap-scan ADS-L frequencies (868.2, 868.4, 869.525 MHz). Decode
ADS-L position broadcasts.

What done looks like:
- Decoded ADS-L packets appear in the Meshtastic log with correct
  position and identification data.
- Bench test report with same metrics.

### Story 2.13: Surface received traffic into the mesh (fork)

Status: todo
Depends on: 2.10
Track: Mezulla fork

Decoded traffic from any protocol (2.10, 2.11, 2.12) is surfaced
as Meshtastic telemetry messages (reusing the traffic message type
allocated in story 2.5, or a fork-specific port if upstream
allocation didn't cover receive). Any node in the mesh can relay
them. A ground node hearing FANET traffic injects it into the mesh
for airborne nodes out of direct range.

What done looks like:
- A ground Meshtastic node receives a FANET packet and relays it
  through the mesh.
- A node two hops away receives the traffic telemetry.
- Traffic messages are tagged with aircraft type + protocol source.
- Bench test report shows mesh overhead from traffic telemetry
  relay (additional airtime, packet count).

---

## Future work (v2 Mezulla hardware)

### Dual radio support

On the custom Mezulla v2 board with a second SX1276 dedicated to
traffic, the module listens full-time on FANET/FLARM/ADS-L without
gap-scanning. Primary radio stays on Meshtastic with zero
interruption. Single-radio devices (v1) continue working via
gap-scanning.

Promoted to active work if story 2.10 shows gap-scanning is not
viable on a single radio.

---

## Open questions

1. **Gap-scan duty cycle.** How much time can we steal from
   Meshtastic RX for traffic scanning before mesh performance
   degrades noticeably? Answered by story 2.10's bench test.
2. **FLARM encryption key schedule.** The reverse-engineered
   Legacy protocol uses time-based keys. Verify the SoftRF
   implementation is current with recent FLARM firmware updates.
3. **ADS-L Issue 2 packet format.** EASA published Issue 2 in
   2025. Verify the spec is stable enough to implement against.
4. **Meshtastic module API boundaries.** Does the current module
   system support radio retuning, or does this need deeper
   integration? Answered by story 2.5's upstream engagement.
5. **CPA threshold tuning.** What distance/time thresholds
   minimize false alarms while catching real threats? Start
   conservative (500m / 30s), tune from flight data.
6. **Acceptable mesh loss threshold.** What packet loss rate is
   acceptable for gap-scan receive? Meshtastic maintainers set the
   bar for upstream; we set our own bar for the fork. Drives the
   go/no-go on gap-scanning vs. dual radio.

## Related docs

- `docs/hardware/traffic-awareness.md` — hardware design decisions,
  protocol specs, legal basis for FLARM reverse engineering
- `docs/hardware/mcu.md` — ESP32-S3 dual-SPI for two radios
- `docs/backlog/epic-01-peer-awareness-and-sos.md` — buddy mesh
  (prerequisite — must reach MVP before this epic starts)
- [SoftRF](https://github.com/lyusupov/SoftRF) — multi-protocol
  reference implementation
- [GXAirCom](https://github.com/gereic/GXAirCom) — paragliding
  FANET+FLARM reference
- [FANET protocol spec](https://github.com/3s1d/fanet-stm32/blob/master/Src/fanet/radio/protocol.txt)
