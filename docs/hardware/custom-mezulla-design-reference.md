# Custom Mezulla — Design Reference

A working reference for the eventual custom Mezulla board: the open-source
Tern hardware that will replace the off-the-shelf LilyGo T3.

This is not a finalized BOM. It is the document you read before you start
writing one, so the decisions that are already made don't get re-litigated
and the open questions are visible.

## Status

- **Current platform:** off-the-shelf LilyGo TTGO LoRa32 T3 V1.6.1
  ("mezulla"), US 915 MHz, Meshtastic 2.7.15 pinned. See
  `tern-android/scripts/flash-mezulla.sh`.
- **Custom board:** not started. Will not start until the LilyGo Mezulla
  is demonstrably working in real flights (see experiments below).
- **Competition target:** Seeed Studio Meshtastic Build-Off 2026 — enter
  if the LilyGo v1 validates the design thesis in real flights.
- **Nothing in this document is verified hardware.** These are design
  intents. Real measurements override anything written here.

---

## Architectural principles

Cross-cutting ideas that shaped every decision below.

1. **Standalone flight beacon.** GPS + baro + IMU on board. Mezulla
   broadcasts position/vario/SOS independent of the phone.
2. **Two-phase operation.** Phase 1 (pre-pairing / phone-absent):
   Mezulla display + speaker are primary. Phase 2 (paired): phone is
   primary, Mezulla audio is backup.
3. **Modality split.** Visual = setup (e-ink for QR pairing). Audio =
   in-flight (speaker for events + vario + alerts). Pilots don't look
   at Mezulla while flying.
4. **Single-domain device.** One USB-C charge-in only. No power-bank
   role, no phone-power path.
5. **Open source non-negotiable.** Every chip has a documented
   alternative so the design outlives any single supplier.
6. **Pilot-replaceable consumables.** Cells swappable in the field
   without tools.
7. **Traffic awareness is upstream, not custom.** FANET + FLARM + OGN
   support belongs as a Meshtastic module PR, not custom firmware.
   Grow the ecosystem, don't fork it.

---

## Decisions card

Each row links to the detail file with full rationale, alternatives
evaluated, and trade-offs.

| Domain | Decision | Detail |
|---|---|---|
| Power | Single-domain, one USB-C charge-in only | [power.md](power.md) |
| Cell | 18650 cylindrical (21700 future, never pouch) | [power.md](power.md) |
| Chemistry | NMC v1, LFP flagged for v2 | [power.md](power.md) |
| Sourcing | Tier-A cells only (Panasonic, Samsung, LG, CATL, Molicel) | [power.md](power.md) |
| MCU | ESP32-S3 v1; nRF54LM20A future path | [mcu.md](mcu.md) |
| Radio | SX1276, reuse LilyGo matching network | [radio-and-antenna.md](radio-and-antenna.md) |
| Antenna | SMA connector; mounting dominates spec | [radio-and-antenna.md](radio-and-antenna.md) |
| GPS | ublox M8N/M10S + active patch antenna | [sensors.md](sensors.md) |
| Baro | BMP388 primary, DPS310 alternative | [sensors.md](sensors.md) |
| IMU | ICM-42688 6-axis (vario fusion + crash detect) | [sensors.md](sensors.md) |
| Display | 1.54" e-ink (setup + fallback only) | [display-audio-input.md](display-audio-input.md) |
| Audio | MAX98357A + speaker (primary in-flight) | [display-audio-input.md](display-audio-input.md) |
| Input | Bourns PEC11R crown + backup button | [display-audio-input.md](display-audio-input.md) |
| Connectors | USB-C, SMA, 18650 sled, no breakouts | [display-audio-input.md](display-audio-input.md) |
| Traffic awareness | FANET + FLARM + OGN as Meshtastic module PR | [traffic-awareness.md](traffic-awareness.md) |
| Dual radio | Second SX1276 for dedicated traffic RX (~$3) | [traffic-awareness.md](traffic-awareness.md) |

**Alternative chip sources** for supply resilience:

| Function | Primary | Alternative(s) |
|---|---|---|
| LoRa radio (mesh) | Semtech SX1276 | SX1262, AI-Thinker Ra-02 modules |
| LoRa radio (traffic) | Semtech SX1276 | SX1262 |
| MCU | Espressif ESP32-S3 | Nordic nRF54LM20A (future) |
| GPS | ublox NEO-M8N / M10S | Quectel L86/L96, Allystar |
| Barometer | Bosch BMP388 | Infineon DPS310, TDK ICP-10125, TE MS5611 |
| IMU | TDK ICM-42688 | ST LSM6DSO |
| Audio amp | MAX98357A (I2S) | PAM8302A (analog) |
| Charge IC | TP4056 | MCP73831, BQ24074 |

---

## Open questions

Not decided. Resolve with data.

1. **GPS cold-start at alpine cold** (-10 C to -20 C, 3000m+).
2. **Crash-detection threshold tuning.** How many G's = real crash
   vs hard landing vs fumbled launch?
3. **Audio language design.** Specific tones/patterns for each event.
4. **MPR121 capacitive touch through flying gloves** — supplement
   or partially replace the crown?
5. **Mechanical / enclosure design.** Material, harness mounting,
   antenna routing.
6. **LFP vs NMC for v2.** Safety advantages vs capacity loss +
   charge-circuit redesign.
7. **Sharp Memory LCD vs e-ink** — only if vario-on-device becomes
   a real goal.

---

## Pre-design experiments

Run on existing hardware (LilyGo + SunFounder kit) before custom-board
design commits. Ordered by leverage.

1. **Fly the LilyGo as-is** on your harness for a real flying day.
   Battery runtime, sunlight readability, ergonomics, antenna survival.
2. **Antenna position experiment.** Three mount positions, log RSSI
   delta. Expected: 10-20 dB.
3. **Outdoor WS2812 LED visibility.** Midday sun, through sunglasses.
   Decides if RGB LED status supplements the display.
4. **MPR121 cap-touch through flying gloves.** Decides if capacitive
   input supplements the crown.
5. **Two-node LoRa range test.** Second LilyGo (~$30), one at launch,
   fly with one, log packet receipt vs distance/altitude.
6. **Bench battery runtime.** Known cell, field profile, measure real
   cutoff time.
7. **Audio language character study.** SunFounder amp + speaker,
   iterate on event tones in wind noise.

**Gate: do not start custom-board design until experiments 1, 2, and 5
have run with captured evidence.**

---

## Explicitly out of scope / rejected

Re-opening requires new constraints.

- **Integrated power-bank role** — triples size, couples failure modes
- **USB-C power pass-through** — no value, only failure surface
- **LiPo pouch cells** — single-supplier, not swappable, worse crash survival
- **No-name / counterfeit cells** — documented fire risk on a body-worn device
- **BME688 pressure+humidity combo** — pressure noise 4x worse than BMP388
- **9-axis IMU with magnetometer** — unreliable heading in paragliding cockpit
- **BNO080 smart IMU** — expensive, calibrated for VR not vario
- **Standard OLED / TFT for in-flight** — unreadable in direct sun
- **3-color e-ink** — 15 s refresh, too slow
- **Directional antennas** — defeats omnidirectional buddy-mesh
- **Custom RF matching from scratch** — reuse proven reference design
- **Closed-source BOM components** — open source non-negotiable

---

## Related docs

- `tern-android/scripts/flash-mezulla.sh` — current LilyGo flashing
- `docs/architecture/meshtastic-connection.md` — the abstraction Mezulla
  connects through
- `docs/backlog/current-focus.md` — what we're shipping now

---

## Maintenance

- When experiments produce real measurements, update the relevant
  detail file with the measured value and date.
- When an open question gets answered, move it to the decisions card
  and add detail to the appropriate file.
- When a decision gets reversed, keep the old reasoning with a note
  explaining what changed — future contributors need the trail.
