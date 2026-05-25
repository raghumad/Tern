# Radio and Antenna

Back to [index](custom-mezulla-design-reference.md).

---

## Radio: SX1276, reuse LilyGo matching network

- **Chip:** SX1276 as on the LilyGo. SX1262 is a future upgrade
  candidate (better sensitivity, lower TX current) but its matching
  network is different and the upgrade isn't justified for v1.
- **RF matching:** lift the proven components from the LilyGo
  reference design rather than designing from scratch. RF redesign
  is weeks of pain for no v1 product gain.
- **Region pinning:** US 915 MHz / EU 868 MHz — distinct board
  variants or a no-populate region-strap component. Wrong-region
  matching damages the PA over time (high VSWR).

---

## Antenna: SMA connector; mounting dominates antenna spec

- **Connector:** SMA female on the board. Pilot-swappable antennas.
  Don't solder-mount.
- **Stock antenna:** quality 915 MHz omni at ~5 dBi from a real
  industrial brand (Linx, Pulse, Taoglas). Not a generic Amazon stub.
- **Mechanical layout:** the board orientation in its expected
  harness mount must naturally place the antenna pointing **up and
  out away from the pilot's body**. This is the highest-leverage
  hardware decision in the whole design.

**Why mounting dominates:** human body absorbs ~20 dB at 915 MHz.
An antenna sandwiched against the pilot loses more signal to
absorption than any reasonable antenna upgrade can recover. The
custom Mezulla mechanical design must enforce good RF posture by
the shape of the device itself.

---

## Antenna tuning dimensions

| Dimension | What's at stake |
|---|---|
| Frequency match | Wrong-band antenna = high VSWR, power reflected at radio, PA damage over time |
| Gain (dBi) | More gain extends range in pattern but squashes omni doughnut |
| Polarization | Cross-polarization between TX/RX can cost up to 20 dB (game over) |
| SWR | > 2:1 is problematic; both antenna and radio want 50 ohm |
| **Mounting / environment** | **Human body absorbs ~20 dB at 915 MHz — by far the largest practical loss in wearable form factor** |
| Coax losses | Cheap RG-174: ~1 dB/m at 915 MHz |
| Connector quality | Stock SMA leaks, oxidizes, wears over connect cycles |

**Single most important takeaway:** the mounting decision is the
antenna decision. A great antenna mounted against the pilot's body
loses to a mediocre antenna mounted clear of it.

---

## Actions on existing hardware

**Free / Tier-1 actions:**
- Test three mount positions, log RSSI delta
- Verify antenna is roughly vertical in flight posture
- Verify stock antenna is actually 915 MHz, not the wrong band

**Cheap upgrades (~$10-30) if mount test shows antenna is still limit:**
- Quality 915 MHz omni at ~5 dBi from Linx / Pulse / Taoglas
- Half-wave (~16 cm) or 5/8-wave (~20 cm) 915 MHz omni
- Short LMR-100 pigtail to remote-mount the antenna clear of body

**Out of scope:**
- Directional Yagi antennas (defeats omnidirectional buddy-mesh)
- Circular polarization (~3 dB loss vs ideal linear, hard to
  source for 915)
- Active antennas / LNAs (overkill — SX1276 sensitivity already
  world-class)

---

## Software-side equivalent of an antenna upgrade

Meshtastic's spreading factor (SF). SF12 vs SF7 is ~6-9 dB more
sensitivity — equivalent to a 5 dBi antenna upgrade, for free.
Worth checking `meshtastic --info` for the current `lora.modem_preset`.
