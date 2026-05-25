# Traffic Awareness: FANET, FLARM, OGN

Back to [index](custom-mezulla-design-reference.md).

---

## Decision: contribute aviation protocols to Meshtastic upstream

Traffic awareness (seeing other aircraft, being seen by them) is not
a custom-firmware feature. It belongs in Meshtastic as a module PR.

**Approach:** submit a Meshtastic PR that adds a traffic awareness
module supporting FANET, FLARM (reverse-engineered), and OGN. Iterate
from maintainer and community feedback on the PR itself — no RFC, no
design-by-committee.

**Why upstream, not custom firmware:**
- Meshtastic has 50k+ users and an established module system.
  Forking throws away that ecosystem for no reason.
- Every Meshtastic device already has GPS + LoRa. The module
  makes them visible to the aviation world with minimal code.
- "Paragliders are joining the party" is a better pitch than
  "we built our own thing."
- Upstream means every Meshtastic firmware update, security fix,
  and new feature comes free. Custom firmware means maintaining
  a fork forever.

**What the module does:**

*Transmit (making Meshtastic pilots visible to aviation):*
- Broadcast device position as a FANET Type 1 tracking packet
  (11 bytes: lat, lon, altitude, speed, climb, heading, aircraft type)
  on the FANET frequency (868.2 MHz EU / 920.8 MHz US).
- Broadcast as FLARM Legacy protocol on FLARM frequencies.
- Packet takes ~5 ms airtime at SF7. Retune, transmit, retune back.
  Minimal mesh disruption.
- Every Skytraxx, XCtracer, Oudie, and FLARM device in the air
  now sees the pilot. Safety value is immediate.

*Receive (seeing aviation traffic from Meshtastic):*
- During Meshtastic RX idle gaps, retune to FANET/FLARM frequency,
  listen for traffic packets, retune back.
- Decode FANET Type 1, FLARM Legacy, OGN tracker packets.
- Surface nearby traffic as Meshtastic telemetry — alert the mesh
  channel so every pilot in the buddy group gets the traffic warning.
- Single-radio gap-scanning won't catch 100% of traffic. Some
  awareness is infinitely better than none.

*Custom board with dual radio:*
- Second SX1276 dedicated to FANET/FLARM/OGN RX full-time.
- Primary radio stays on Meshtastic with zero interruption.
- ~$3 BOM cost. The clean architecture for the custom Mezulla board.

---

## Protocols

### FANET (open, no legal risk)

Flying Ad-hoc Network. Open protocol, open source implementations.
Built specifically for paragliders and gliders on LoRa.

- **EU:** 868.2 MHz, BW 250 kHz, SF7, 14 dBm, <1% duty cycle
- **US:** 920.8 MHz, BW 500 kHz, SF7, 14 dBm
- **Syncword:** 0xF1 (SX1276) / 0xF414 (SX1262)

Key message types:
- **Type 1 — Tracking:** lat/lon, altitude, speed, climb rate,
  heading, aircraft type. 11 bytes. The core packet.
- **Type 9 — Thermal:** position, altitude, average climb, wind
  speed/heading. Pilots broadcasting thermals to each other.
- **Type 7 — Ground tracking:** position + status (walking, vehicle,
  distress).
- **Type 3 — Message:** text messaging between devices.
- **Type 5 — Landmarks:** airspace warnings, keep-out zones,
  touchdown areas.

TX interval: floor((neighbors/10 + 1) * 5s). Self-throttling.

Reference implementations:
- [fanet-stm32](https://github.com/3s1d/fanet-stm32) — original
- [GXAirCom](https://github.com/gereic/GXAirCom) — paragliding-focused
- [SoftRF](https://github.com/lyusupov/SoftRF) — multi-protocol

Devices that speak FANET: Skytraxx (Beacon, 2.1, 5), Naviter
(Oudie Omni, Hyper), Skybean (Skydrop), XCTracer, Burnair.

### FLARM (proprietary, reverse-engineered, legally defensible)

The de facto collision avoidance standard in gliding and light
aviation. Proprietary protocol encrypted by FLARM AG (Switzerland).

**Why include it despite being proprietary:**
- It's a collision avoidance system. Locking safety behind a paywall
  means pilots who can't afford FLARM hardware are invisible to
  every FLARM-equipped aircraft sharing the same thermal.
- ADS-B (the system commercial aviation uses) is open and
  regulator-mandated. Nobody argued encrypting it would help.
- The encryption doesn't prevent jamming or spoofing — anyone with
  a $30 SDR can do both. It only prevents interoperability.
- EASA is pushing ADS-L as the open replacement. The regulatory
  direction is toward openness.

**Legal basis for reverse engineering:**
- **EU Software Directive (2009/24/EC), Article 6:** decompilation
  is permitted when necessary to achieve interoperability with
  independently created software.
- **US precedent:** Sega v. Accolade (1992), Sony v. Connectix
  (2000) — reverse engineering for interoperability is fair use.
- **Practical reality:** SoftRF has shipped reverse-engineered FLARM
  ("Legacy" protocol) for years. FLARM AG has sent threats but
  never successfully sued — because the law is clear.
- **Worst case:** a cease-and-desist letter. The interoperability
  defense is strong. A company arguing "you're not allowed to see
  the glider about to hit you" doesn't survive public scrutiny.

Reference implementation: SoftRF "Legacy" protocol.

### OGN — Open Glider Network (open, no legal risk)

Open tracking protocol for gliders. Ground stations aggregate
positions and publish them online. OGN tracker protocol runs on
the same LoRa hardware.

Reference: [ogn-aprs-protocol](https://github.com/glidernet/ogn-aprs-protocol)

---

## Hardware implications for custom Mezulla board

**Dual radio architecture decided:**

| Radio | Frequency | Protocol | Role |
|---|---|---|---|
| Primary SX1276 | Meshtastic channel (869.5 MHz EU / 906.9 MHz US) | Meshtastic | Buddy mesh, text, telemetry |
| Secondary SX1276 | 868.2 MHz EU / 920.8 MHz US | FANET + FLARM + OGN | Traffic awareness RX full-time, TX periodic |

- Both radios share the same SMA antenna (diplexer or antenna
  switch, since frequencies are close but not simultaneous TX).
  Or: two separate antenna paths if isolation is needed.
- ESP32-S3 has enough SPI instances for two radios.
- BOM cost for second radio: ~$3.
- The second radio also enables ground-station mode (receive and
  relay traffic to OGN when Mezulla is powered at a launch site).

**For the current LilyGo (single radio):**
- Stay on Meshtastic. Buddy mesh is the primary mission.
- Once the Meshtastic traffic module PR lands, the LilyGo can do
  gap-scanning for FANET/FLARM during idle windows.
- Not perfect coverage, but better than zero.

---

## Existing open-source projects (reference, not dependencies)

| Project | Focus | Protocols | Hardware | Notes |
|---|---|---|---|---|
| [SoftRF](https://github.com/lyusupov/SoftRF) | GA proximity awareness | FLARM, FANET, OGN, ADS-B, ADS-L, PilotAware | ESP32, nRF52840, many others | 979 stars, very active, GPL-3.0. Multi-protocol reference. |
| [GXAirCom](https://github.com/gereic/GXAirCom) | Paragliding FANET+FLARM | FANET+, FLARM, OGN | ESP32 (T-Beam) | 159 stars, paragliding-specific, has vario + messaging + Android app |
| [fanet-stm32](https://github.com/3s1d/fanet-stm32) | FANET reference impl | FANET | STM32 | Original protocol implementation by FANET creator |

These are reference for understanding the protocols, not
dependencies to pull in. The Meshtastic module should be
self-contained.
