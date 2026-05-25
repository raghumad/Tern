# Power, Cell, and Sourcing

Back to [index](custom-mezulla-design-reference.md).

---

## Power architecture: single-domain device

- **One USB-C port** on the board, for charging Mezulla itself. Nothing
  else.
- **No phone-charging output.** No power-bank role.
- **No power pass-through.** Mezulla does not sit in the cable path
  between the pilot's power bank and the phone.
- Pilot's PD power bank cables directly to the phone, separately.

**Why:** integrating a power-bank role triples device size (3-cell
pack needed for realistic phone top-up over a long flight), couples
failure modes (mesh and phone die together), duplicates a commodity
feature ($15 buys a 10000 mAh pocket bank), and forces a capacity
choice on pilots who all need different amounts. Pass-through adds
no value either — pilots' PD banks already cable straight to the
phone with one cable.

**When to revisit:** if a "Mezulla v2 / Tern flight computer" concept
emerges with much larger display, dedicated UI surface, etc. — at
that point the device becomes visibly central to the flight and the
integrated-battery question is worth re-opening.

---

## Cell form factor: 18650 (21700 as future option, never pouch)

- **Default:** 18650 cylindrical. Continuity with existing LilyGo
  design — same JST connector, same charge circuit, same mechanical
  envelope.
- **Future variant** if cold-weather capacity becomes tight: 21700
  (21 mm x 70 mm, ~5000 mAh per cell).
- **Never LiPo pouch.** Considered and rejected.

**Why cylindrical wins:**
- **Pilot-swappable.** Carry a charged spare for back-to-back flying
  days. Sealed pouches = ship-for-service when end-of-life.
- **Open-source sourcing.** Anyone can buy a replacement 18650
  anywhere on Earth in 2030. Custom pouches die with their supplier.
- **Crash / harness survival.** Steel can beats soft pouch when
  strapped to a pilot's body through launch dust, top-landings, falls.
- **Continuity with LilyGo.** Charge circuit, JST connector,
  mechanical thinking transfer to custom-board v1 without re-learning.
- **Single 18650 already covers a flying day** with margin (see
  runtime math below). Capacity is not the constraint.

---

## Cell chemistry: NMC for v1, LFP option flagged for v2

- **v1 default:** NMC (Panasonic NCR series, Samsung 30Q, LG HG2,
  etc.). 3.7 V nominal, 4.2 V full, 3.0 V cutoff.
- **LFP option** (LiFePO4) flagged for v2 evaluation. Safety profile
  is genuinely attractive for a body-worn device:
  - Stable to 250 C+; no oxygen release during failure
  - 3-5x cycle life vs NMC
  - 3.2 V nominal, requires 3.65 V max charge circuit (not the
    LilyGo's 4.2 V TP4056)
  - ~30% capacity loss for same cell size
  - Worse cold weather behavior than premium NMC

**Why NMC for v1:** LilyGo TP4056 charge circuit is set for NMC.
Switching means redesigning the charger — v1-scope-blowing. Stay
NMC for v1; evaluate LFP for v2.

---

## Cell sourcing quality tiers

Buy authentic Tier-A cells from a reputable distributor. Not Amazon.

| Tier | What it is | Use for Mezulla? |
|---|---|---|
| **A — Top global manufacturers** | Panasonic/Sanyo, Samsung SDI, LG Chem (LGES), Sony/Murata, **CATL**, Molicel | Yes — this tier only for anything strapped to a pilot |
| **B — Real second-tier OEM** | EVE, BAK, BYD, Lishen | Yes if sourced through industrial distributors |
| **C — Salvaged "pulls"** | Cells harvested from used EV / laptop packs | Bench experiments only |
| **D — No-name / counterfeit** | "UltraFire", "9900 mAh" listings, generic Amazon | Never. Documented fire risk |

**Recommended specific models (currently in production):**
- 18650: **Panasonic NCR18650B** (3400 mAh), **Samsung 30Q** (3000 mAh),
  **LG HG2** (3000 mAh), **Molicel P28A** (2800 mAh)
- 21700: **Samsung 50G/50E** (5000 mAh), **Molicel P42A** (4200 mAh),
  **Sony/Murata VTC6A** (4000 mAh)

**Reputable distributors:**
- US: Liion Wholesale, IMR Batteries, 18650BatteryStore, Illumn
- EU: Nkon (NL), Akkuteile (DE), Fogstar (UK)
- Cross-check: [lygte-info.dk](https://lygte-info.dk) — HKJ's
  independent capacity test database

**Note on Tier-A:** CATL is included by quality, not by retail
visibility. They dominate EV (~37% global market share) and pioneered
automotive-scale LFP. Reason CATL is harder to find at retail than
Panasonic/Samsung is distribution channels, not quality.

**Open-source community guidance** (when the custom Mezulla BOM ships):
- Specify minimum: >=2800 mAh, real continuous discharge >=3 A,
  Tier-A manufacturer
- List recommended cells with distributor links
- Explicit warning against no-name cells with the safety rationale
- **Don't ship cells with the open-source kit.** Pilot sources their
  own. Shipping lithium across borders is regulated; shifting the
  responsibility to the pilot is correct.

---

## Cell sourcing quick card

Buy from a reputable distributor (see list above). Verify these
signals before trusting:

- Datasheet exists and is published
- Capacity <= 3500 mAh for 18650, <= 5000 mAh for 21700
- Continuous discharge claim matches the chemistry (NCR18650B
  is ~3.4 A, not "20 A")
- Weight >= 42 g (18650) / >= 65 g (21700)
- Price in the $5-10 range for genuine Tier-A 18650
- Brand spelled correctly (counterfeits often have minor typos)

---

## Runtime math

Paper-napkin calculations. Replace with measured numbers from
the bench battery experiment when available.

**Inputs:**
- 18650 nominal capacity: 3000 mAh (good cell, average grade)
- Practical usable after boost-converter losses + 4.2 to 3.0 V usable
  range + cold derating: ~2400 mAh

**LilyGo + Meshtastic average draw by mode (estimated):**

| Mode | Avg current | Notes |
|---|---|---|
| Aggressive power-save | ~25 mA | OLED off, BLE off, LoRa periodic TX only |
| Default Meshtastic | ~80 mA | BLE on, LoRa RX continuous, OLED auto-sleep |
| Field flight profile | ~50 mA | OLED disciplined, BLE on for phone, LoRa beacon |
| Wi-Fi enabled (dev only) | ~180 mA | Above + Wi-Fi radio always on |

**Runtime = 2400 mAh / draw:**

| Mode | Runtime |
|---|---|
| Aggressive | ~96 h (~4 days continuous) |
| Default | ~30 h (~1.5 days continuous; week of 4h flying days) |
| Flight profile | ~48 h (~6 full flying days) |
| Wi-Fi on (dev only) | ~13 h (one long flying day) |

**Conclusion:** battery is not the constraint for any single
flying day; weekend XC trips fit one charge; week-long XC trips
fit one charge if Wi-Fi stays off.

**Custom board adds:** ~30-40 mA from GPS + baro + IMU active.
Brings field-flight profile to ~80-90 mA. Still one cell per day
with margin.

**Caveats:**
- Cold weather (0-5 C alpine flying) cuts capacity 15-25%
- Cheap cells deliver 50-70% of labeled capacity (use Tier-A!)
- OLED full-bright continuously adds ~15 mA — cuts default
  runtime from 30 h to ~22 h. With e-ink, this concern goes away.
