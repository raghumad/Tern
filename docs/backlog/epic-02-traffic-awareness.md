# Epic 02: Pilots see nearby aircraft and are seen by them

Status: todo
Priority: after Epic 01 MVP

## Why this matters

A paraglider sharing a thermal with a glider, or flying near a
helicopter doing mountain rescue, has one critical unknown: "Is
anything about to hit me?" Today, only pilots who buy proprietary
FLARM hardware get collision awareness. Everyone else is invisible
to each other.

This epic makes every Meshtastic device — flying or on the ground —
a participant in aviation traffic awareness. Flying pilots broadcast
their position to the aviation world (FANET, FLARM, ADS-L). Ground
nodes relay traffic they hear into the mesh. Any pilot in the mesh
gets warned when something is converging on them.

The protocols are added as a Meshtastic module PR — not custom
firmware, not a fork. Paragliders join the Meshtastic ecosystem,
they don't leave it.

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

## Out of scope

- ADS-B 1090 MHz reception (needs a separate receiver IC — future
  hardware, not this epic).
- Full FLARM-style trajectory prediction with wind modeling.
  Simple CPA (dot product on position + velocity vectors) is
  sufficient and honest.
- Custom Meshtastic firmware. Everything ships as a module within
  upstream Meshtastic.

## Stories

### Story 2.1: Receive and decode FANET tracking packets

Status: todo

Gap-scan the FANET frequency (868.2 MHz EU / 920.8 MHz US) during
Meshtastic RX idle windows. Decode Type 1 (aircraft tracking) packets
into position, altitude, speed, climb rate, heading, aircraft type.

What done looks like:
- On a bench with two devices (one running GXAirCom or SoftRF
  transmitting FANET, one running the module), decoded FANET packets
  appear in the Meshtastic log with correct position data.
- No measurable degradation of Meshtastic mesh performance during
  gap-scanning.

### Story 2.2: Receive and decode FLARM Legacy packets

Status: todo

Gap-scan FLARM frequencies and decode reverse-engineered Legacy
protocol packets. Same output format as 2.1 — position, altitude,
speed, heading.

What done looks like:
- Decoded FLARM packets from a real FLARM device (or SoftRF in
  Legacy mode) appear in the Meshtastic log.
- Legal: source code includes the EU Software Directive
  interoperability citation in the file header.

### Story 2.3: Receive and decode ADS-L SRD860 packets

Status: todo

Gap-scan ADS-L frequencies (868.2, 868.4, 869.525 MHz). Decode
ADS-L position broadcasts.

What done looks like:
- Decoded ADS-L packets appear in the Meshtastic log with correct
  position and identification data.

### Story 2.4: Surface traffic as Meshtastic telemetry

Status: todo

Decoded traffic from any protocol (2.1, 2.2, 2.3) is surfaced as
Meshtastic telemetry messages. Any node in the mesh can relay them.
A ground node hearing FANET traffic injects it into the mesh for
airborne pilots who are out of direct range.

What done looks like:
- A ground Meshtastic node receives a FANET packet and relays it
  through the mesh.
- An airborne pilot two hops away sees the traffic on the Tern map.
- Traffic telemetry messages are distinguishable from regular
  Meshtastic position packets (tagged with aircraft type + protocol
  source).

### Story 2.5: Broadcast own position on FANET

Status: todo

When the device is airborne (GPS altitude change + speed above
walking threshold), transmit a FANET Type 1 packet with the
device's position, altitude, speed, climb rate, heading, and
aircraft type. Gated: ground devices never broadcast Type 1.

What done looks like:
- A Skytraxx or GXAirCom device on the bench receives and displays
  the Meshtastic device's position as a paraglider.
- A stationary device on the ground does not appear as an aircraft
  on any FANET receiver.
- Aircraft type is configurable (paraglider, hang glider, glider).

### Story 2.6: Broadcast own position on FLARM Legacy

Status: todo

Same as 2.5 but on FLARM Legacy protocol. Every FLARM device in
range sees the pilot.

What done looks like:
- A real FLARM device (or SoftRF in Legacy RX mode) receives and
  displays the Meshtastic device's position.
- Same airborne gate as 2.5 — ground devices never broadcast.

### Story 2.7: Broadcast own position on ADS-L

Status: todo

Same as 2.5 but on ADS-L SRD860. The regulator-backed open standard.

What done looks like:
- An ADS-L capable device receives and displays the position.
- Same airborne gate.

### Story 2.8: CPA computation and collision alert

Status: todo

For each detected aircraft, compute closest point of approach against
own position and velocity. If CPA distance < threshold and time to
CPA < horizon, alert.

The math: relative position dot relative velocity, solve for time,
compute distance at that time. One dot product per aircraft per
update.

What done looks like:
- In the swarm simulator, a virtual FANET aircraft converging on the
  pilot triggers an alert.
- Alert includes direction and rough distance ("traffic, 500m, west").
- Non-converging traffic (parallel, diverging) does not alert.
- False-alarm rate on real flight data (IGC replays) is acceptable.

### Story 2.9: Audio traffic alert on Mezulla speaker

Status: todo

CPA alerts from 2.8 produce a distinctive audio tone on Mezulla's
speaker. The pilot hears "traffic nearby" without looking at
anything.

What done looks like:
- Traffic alert tone is distinct from buddy-joined, SOS, and vario
  tones.
- Audible in a paragliding cockpit with wind noise.
- Does not fire for distant non-threatening traffic.

### Story 2.10: Dual radio support for custom Mezulla board

Status: todo

On the custom board with a second SX1276 dedicated to traffic, the
module listens full-time on FANET/FLARM/ADS-L without gap-scanning.
Primary radio stays on Meshtastic with zero interruption.

What done looks like:
- Second radio receives 100% of traffic packets (no gap-scan
  misses).
- Meshtastic mesh performance is identical to single-radio mode
  (no retune pauses).
- Single-radio devices still work via gap-scanning (graceful
  degradation).

## Open questions

1. **Gap-scan duty cycle.** How much time can we steal from
   Meshtastic RX for traffic scanning before mesh performance
   degrades noticeably? Needs bench measurement.
2. **FLARM encryption key schedule.** The reverse-engineered
   Legacy protocol uses time-based keys. Need to verify the
   SoftRF implementation is current with recent FLARM firmware
   updates.
3. **ADS-L Issue 2 packet format.** EASA published Issue 2 in
   2025. Verify the spec is stable enough to implement against.
4. **Meshtastic module API.** Does the current module system
   support radio retuning, or does this need a deeper integration?
   Read the Meshtastic source before starting.
5. **CPA threshold tuning.** What distance/time thresholds
   minimize false alarms while catching real threats? Start
   conservative (500m / 30s), tune from flight data.

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
