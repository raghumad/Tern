# Custom Mezulla — Design Reference

A working reference for the eventual custom Mezulla board: the open-source
Tern hardware that will replace the off-the-shelf LilyGo T3. This document
captures the hardware decisions made so far, the reasoning behind each,
the experiments that should run *before* design commits, and the parts of
the BOM that are still open.

This is not a finalized BOM. It is the document you read before you start
writing one, so the decisions that are already made don't get re-litigated
and the open questions are visible.

## Status

- **Current platform:** off-the-shelf LilyGo TTGO LoRa32 T3 V1.6.1
  ("mezulla"), US 915 MHz, Meshtastic 2.7.15 pinned. See
  `tern-android/scripts/flash-mezulla.sh`.
- **Custom board:** not started. Will not start until the LilyGo Mezulla
  is demonstrably working in real flights (see "Pre-design experiments"
  below).
- **Nothing in this document is verified hardware.** These are design
  intents based on engineering reasoning and open-source community
  knowledge. Real measurements override anything written here.

## How to use this document

- **Designing the custom board?** Read "Architectural principles" and
  "Decisions so far" first, then check the "Open questions."
- **Sourcing parts for the existing LilyGo?** The cell sourcing tiers
  and antenna tuning sections apply to the LilyGo too.
- **Proposing a change to a decision?** Bring new constraints or new
  data. The decisions list cites *why*; bring something that addresses
  the why.

---

## Architectural principles

Cross-cutting principles that shaped multiple decisions. Read these
first — they explain why the individual decisions hang together.

### Mezulla is a standalone flight beacon, not a comms relay

With on-board GPS + baro + IMU, Mezulla can broadcast its position,
vario data, and SOS state independent of the phone. The phone enhances
the experience; the phone is not required for the device to be useful.

**Consequences:**
- Sensor integration (GPS, baro, IMU) is in scope for v1
- Crash detection + auto-SOS can happen on the device, no round-trip
- Mezulla's display and speaker must be self-sufficient enough for
  phone-failure cases

### Two-phase operation: pre-pairing vs. paired

The device has two operational phases with different primary
communication surfaces:

| Phase | Primary surface | Mezulla surfaces are... |
|---|---|---|
| **Phase 1 — pre-pairing / phone-absent** | Mezulla itself | Critical (display for QR + speaker for state) |
| **Phase 2 — paired with phone** | Phone | Supporting (speaker still relevant; display rarely consulted) |

This is the foundational mental model. It explains why the display
shrinks in importance (Phase 2 dominates flight time) but why the
speaker stays primary (works across both phases + safety redundancy).

### Modality split: visual = setup, audio = in-flight

Pilots should not need to look at Mezulla in flight. The speaker
handles all in-flight event communication; the display handles
setup (QR pairing) and phone-failure fallback. This matches how
paragliders already operate (continuous vario audio, occasional
glances), rather than importing a smartwatch interaction model that
doesn't fit the use case.

### Single-domain device

Mezulla doesn't try to be a phone power bank, doesn't sit in the
pilot's phone-power cable path, doesn't replace anything the pilot
already carries. It does buddy mesh + on-board sensing + safety
alerts well and nothing else.

### Open source is non-negotiable at every step

Per [[project-tern-hardware-roadmap-and-openness]]. Every chip choice
should ideally have a documented drop-in alternative so the design
outlives any single supplier. Closed-source / proprietary parts in
the BOM should be loudly justified.

### Pilot-replaceable consumables

Cells should be swappable in the field without tools. Spare cells
should be cheap commodities the pilot or community can source
anywhere. Sealed-in batteries break this.

---

## Decisions so far

### Power architecture: single-domain device

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

### Cell form factor: 18650 (21700 as future option, never pouch)

- **Default:** 18650 cylindrical. Continuity with existing LilyGo
  design — same JST connector, same charge circuit, same mechanical
  envelope.
- **Future variant** if cold-weather capacity becomes tight: 21700
  (21 mm × 70 mm, ~5000 mAh per cell).
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
  runtime math). Capacity is not the constraint.

### Cell chemistry: NMC for v1, LFP option flagged for v2

- **v1 default:** NMC (Panasonic NCR series, Samsung 30Q, LG HG2,
  etc.). 3.7 V nominal, 4.2 V full, 3.0 V cutoff.
- **LFP option** (LiFePO₄) flagged for v2 evaluation. Safety profile
  is genuinely attractive for a body-worn device:
  - Stable to 250°C+; no oxygen release during failure
  - 3–5× cycle life vs NMC
  - 3.2 V nominal, requires 3.65 V max charge circuit (not the
    LilyGo's 4.2 V TP4056)
  - ~30% capacity loss for same cell size
  - Worse cold weather behavior than premium NMC

**Why NMC for v1:** LilyGo TP4056 charge circuit is set for NMC.
Switching means redesigning the charger — v1-scope-blowing. Stay
NMC for v1; evaluate LFP for v2.

### Cell sourcing quality tiers

Buy authentic Tier-A cells from a reputable distributor. Not Amazon.

| Tier | What it is | Use for Mezulla? |
|---|---|---|
| **A — Top global manufacturers** | Panasonic/Sanyo, Samsung SDI, LG Chem (LGES), Sony/Murata, **CATL**, Molicel | ✅ This tier only for anything strapped to a pilot |
| **B — Real second-tier OEM** | EVE, BAK, BYD, Lishen | ✅ if sourced through industrial distributors |
| **C — Salvaged "pulls"** | Cells harvested from used EV / laptop packs | Bench experiments only |
| **D — No-name / counterfeit** | "UltraFire", "9900 mAh" listings, generic Amazon | ❌ Never. Documented fire risk |

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
- Specify minimum: ≥2800 mAh, real continuous discharge ≥3 A,
  Tier-A manufacturer
- List recommended cells with distributor links
- Explicit warning against no-name cells with the safety rationale
- **Don't ship cells with the open-source kit.** Pilot sources their
  own. Shipping lithium across borders is regulated; shifting the
  responsibility to the pilot is correct.

### Radio: SX1276, reuse LilyGo matching network

- **Chip:** SX1276 as on the LilyGo. SX1262 is a future upgrade
  candidate (better sensitivity, lower TX current) but its matching
  network is different and the upgrade isn't justified for v1.
- **RF matching:** lift the proven components from the LilyGo
  reference design rather than designing from scratch. RF redesign
  is weeks of pain for no v1 product gain.
- **Region pinning:** US 915 MHz / EU 868 MHz — distinct board
  variants or a no-populate region-strap component. Wrong-region
  matching damages the PA over time (high VSWR).

### MCU: ESP32-S3 for v1; nRF5340 as future upgrade path

- **v1 default:** **ESP32-S3** (Espressif). Dual-core Xtensa @ 240 MHz,
  512 KB SRAM, WiFi + BLE 5.0, native USB.
- **Future upgrade path:** **nRF5340** (Nordic). Dual-core ARM
  (128 MHz app + 64 MHz network), 512 KB RAM, BLE 5.3, no WiFi.
  Superior power efficiency (~1.5 µA sleep vs ~10 µA on ESP32).
- **Evaluated and passed on for v1:** **nRF52840** (Nordic). Single-core
  ARM Cortex-M4F @ 64 MHz, 256 KB RAM. Peripheral buses (4× SPI,
  2× I2C, 2× UART, I2S, hardware QDEC for the crown encoder) map
  cleanly to Mezulla's sensor load, but stacking Meshtastic + 100-200 Hz
  IMU sampling + Kalman-filter vario fusion + audio tone generation on a
  single 64 MHz core with 256 KB RAM makes it a firmware optimization
  project, not a product project.

**Why ESP32-S3 for v1:**
- **Headroom over optimization.** Dual 240 MHz cores + 512 KB RAM
  means Meshtastic + sensor fusion + audio can run without competing
  for cycles. Pin Meshtastic to core 0, sensor fusion + audio to
  core 1 — never think about scheduling.
- **Continuity with LilyGo.** Same Espressif family as the current
  T3. Same toolchain (ESP-IDF / Arduino), same Meshtastic build
  targets, same debugging workflow. Minimal re-learning.
- **WiFi for dev iteration.** Meshtastic TCP API over WiFi (WS4.5)
  enables fast desktop-to-board dev loops. nRF has no WiFi — dev
  goes through BLE or USB serial, both slower.
- **First-class Meshtastic support.** ESP32-S3 is a primary
  Meshtastic platform. No experimental caveats.

**Why nRF5340 (not nRF52840) as the future path:**
- nRF52840's single 64 MHz core is tight for Mezulla's full sensor
  stack. The nRF5340 solves this with a dedicated 64 MHz network core
  (runs BLE autonomously) plus a 128 MHz application core with
  512 KB RAM — closing the gap with ESP32-S3 while delivering
  Nordic's power advantage.
- Meshtastic nRF5340 support is early but growing.

**When to revisit:**
- When the product is stable and power optimization becomes the
  interesting problem (always-on beacon mode, multi-day standby).
- When Meshtastic nRF5340 support matures to first-class.
- Not before both conditions are true.

**Other MCUs evaluated:**
- **STM32WL** (ST): has LoRa radio built into the silicon (SX126x IP),
  but no integrated BLE — needs external BLE module. Meshtastic
  support is experimental/community-maintained. Interesting for a
  future single-chip design, not v1.
- **RP2040** (Raspberry Pi): no built-in wireless at all. Needs
  external modules for both BLE and LoRa. Experimental Meshtastic
  support. Not a fit.
- **CC1352** (TI): Sub-GHz + BLE combo. Limited Meshtastic support.
  Not worth the ecosystem switch.

### Antenna: SMA connector; mounting dominates antenna spec

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
the shape of the device itself. See "Antenna tuning dimensions"
reference below.

### GPS: integrated, ublox M8N or M10S + active patch antenna

- **Chip primary:** ublox M8N or M10S — multi-constellation (GPS +
  GLONASS + Galileo), aviation-grade, well-supported in community
  firmware. M10S is newer with lower power and integrated-antenna
  package option (SAM-M10Q).
- **Active patch antenna** (~25 × 25 mm) on top of the enclosure
  with sky view. Passive chip antennas struggle under tree cover
  and in canyons; not worth the cost saving.
- **Drop-in alternatives** for supply resilience: Quectel L86/L96,
  Allystar. Worth documenting in the BOM as second sources.

**Why integrate GPS (vs. relying on phone GPS over BLE):**
1. **Standalone broadcast.** Mezulla keeps beaconing position even
   when the phone is off/dead. Major safety value on long flights.
2. **Better sky view.** Phone GPS is in your pocket fighting body
   absorption. Mezulla mounted clear-of-body gets significantly
   better fixes, especially cold-start at launch.
3. **Enables on-board safety logic** (crash detection, ground-vs-air
   state, motion-aware beacon rate) without BLE round-trip latency.

**Open question:** cold-weather (-10°C to -20°C) GPS cold-start time
at alpine altitude. Datasheet says fine; real measurement is honest.

### Barometer: BMP388 primary; DPS310 as documented alternative

- **Chip primary:** **Bosch BMP388.** ~0.03 Pa pressure noise floor
  (vario-grade), sub-mA power, ~$2, best-in-class ecosystem support
  (libraries, community knowledge).
- **Drop-in alternative for supply resilience:** **Infineon DPS310.**
  ~0.06 Pa noise floor, often cheaper, real second source. Document
  in BOM.
- **Premium future option** if competition-grade vario noise floor
  justifies it: **TE MS5611** (~0.012 Pa, classic in serious DIY
  varios) or **TDK ICP-10125** (~0.02 Pa, modern capacitive design).

**Note on Bosch market dominance:** Bosch isn't the only or best
maker — Infineon, ST (LPS22HH), TDK (ICP-10125), TE (MS5611),
GoerTek (SPL06-001 used in DJI drones) all make competitive sensors,
several of which beat Bosch on absolute noise floor. Bosch dominance
is smartphone-supply-chain flywheel + library ecosystem, not
technical superiority. BMP388 still wins for v1 because of ecosystem
maturity, but documenting an alternative is part of the open-source
longevity story.

**Note on BME688 / humidity:** there is no "BME388" — Bosch doesn't
make a combined BMP388-quality-pressure + humidity sensor. BME688
adds humidity + gas but its pressure noise floor is ~4× worse than
BMP388. Don't trade vario quality for humidity. If humidity is ever
needed, add a separate **Sensirion SHT40** ($2) mounted where it
gets airflow.

**What baro unlocks:** local vario computation, atmospheric pressure
logging, altitude data independent of GPS (canyon flying). Combined
with IMU (below), enables thermal detection and gust-rejected vario
fusion — see IMU section.

### IMU: ICM-42688 (vario-grade gyro for baro+IMU fusion)

- **Chip primary:** **TDK ICM-42688.** 6-axis (accel + gyro),
  excellent gyro noise floor (DJI-drone-grade), ~$4, low power with
  wake-on-motion interrupt. The gyro quality matters because of
  baro+IMU vario fusion (see below).
- **Drop-in alternatives:** ST **LSM6DSO** (newer than LSM6DSL,
  good gyro, similar power). LSM6DSL is acceptable if only crash
  detection + motion state are needed, not vario fusion.
- **Skip the magnetometer (9-axis).** Magnetic-field heading is
  unreliable in a paragliding cockpit — strong nearby ferrous metal
  (carabiners, harness gear, phone), constant body movement, in-flight
  calibration impossible. Derive heading from GPS course-over-ground
  instead.

**Why IMU on Mezulla:**
1. **Crash detection.** Hard-impact accel spike → immediate auto-SOS
   broadcast over LoRa with last-known GPS. Lifesaving in remote
   terrain.
2. **Motion state classification.** Stationary / walking / flying.
   Lets Mezulla adjust beacon rate dynamically (slow on ground,
   fast in air). Battery saving + better data when it matters.
3. **Pre/post-flight detection.** Auto-start logging on motion +
   altitude change; auto-stop on landing. Removes pilot ceremony.
4. **Baro+IMU vario fusion.** See below — this is the gyro
   investment justification.

**Why vario fusion matters (the gyro investment):**
Pure baro vario is jumpy in turbulence and reports thermal entry
with delay. Baro+IMU fusion (well-established in sailplane
competition varios — LX, Naviter, Skytraxx) gives a cleaner, faster
vario signal: subtracts out gust artifacts, detects real lift 1–3 s
faster than baro alone, smooths turbulence noise. For a buddy-mesh
context, the killer feature is broadcasting **"Luc is +2.8 m/s in
a core at this position"** over LoRa — far more actionable than just
position. Reference projects: XCSoar, Open Variometer Project,
Kobo-vario builds.

### Display: 1.54" e-ink (setup + fallback, not in-flight UI)

- **Primary:** 1.54" 2-color e-ink. Extended-temperature variant
  (Pervasive Displays preferred for cold-weather refresh
  reliability).
- **Alternative supplier:** Waveshare 1.54" if cost dominates
  (cheaper, no extended-temp option — accept slower refresh in
  alpine cold).
- **Role:** setup/pairing QR display + standalone-mode emergency
  display + persistent "I am alive" status. **Not** the in-flight UI
  — speaker + phone handle that.

**Why this size / why e-ink:**
- Per the two-phase model + modality split (above), display is
  consulted rarely during flight. Smaller is fine.
- E-ink reads perfectly in sunlight (better than paper). Critical
  for the pairing QR at launch.
- Zero power between refreshes — display can persist for days,
  showing battery + status, with no battery impact.
- QR codes render beautifully (high contrast, crisp).

**Rejected for the in-flight role:**
- **Standard OLED** (LilyGo SSD1306): unreadable in direct sun.
- **Standard TFT LCD:** unreadable in direct sun without expensive
  transflective treatment.
- **3-color e-ink:** refresh ~15 s — too slow.

**Considered if vario-on-device becomes a real product goal:**
**Sharp Memory LCD** (LS027B7DH01 or similar). Sunlight-readable
like e-ink but with fast refresh (60 Hz capable) — suitable for
continuous vario display on the device itself. More expensive
($15–25). Move to this only if real flight data shows pilots want
vario readout on Mezulla rather than on phone.

### Audio: speaker + I2S amp (primary in-flight communication)

- **Audio amp:** **MAX98357A** (I2S DAC + class-D, mono). Common in
  IoT, well-supported, ~$5.
- **Speaker driver:** small enclosed 4–8 Ω driver, 1–3 W, 20–32 mm.
  PUI Audio AS-2520 or Knowles SR series for premium.
- **Total BOM:** ~$7–10. Trivial.

**Role:**
- **Pre-pairing:** boot confirmation, pairing progress tones,
  pairing complete
- **In-flight events:** buddy joined / went stale / SOS, low battery
- **Vario audio output** (if vario fusion is enabled) — continuous
  variable tones during flight, matching pre-existing pilot
  interaction patterns
- **Safety-critical alert path** independent of phone — works when
  phone is muted / in pocket / dead

**Design dependency:** audio language. What does "buddy joined" vs
"SOS" vs "vario climb" sound like? Needs to be designed and tested
in real flight conditions. Captured as experiment 7 in the
pre-design experiments list — prototypable now on the SunFounder
kit (which has both amp and speaker).

**Audio language principles:**
- SOS is unique, loud, and unmistakable — never confused with
  anything else
- Vario behavior matches what pilots already know (rising pitch
  with climb rate)
- Distinguishable in noisy cockpit (wind, other audio sources)
- Brief, glanceable distinctions (no need to think "was that
  buddy-joined or stale?")

### Input: crown (rotary encoder + push) + single backup button

- **Primary input:** **Bourns PEC11R sealed rotary encoder + push**
  (~$5–8). Side-mounted, knurled metal knob, ~5 mm protrusion.
- **Backup input:** single tactile button elsewhere (combinable
  with power / USB area). Ensures crown isn't a single point of
  failure.
- **Premium / v2 upgrade path:** magnetic encoder (AMS AS5600) with
  custom bearing assembly — contactless sensing, much higher
  durability. Document in BOM.
- **Recessed mount or guard ring** to prevent harness-snag.

**Interaction model:**
- Rotate = scroll / adjust value
- Click = select / confirm
- Long-press (1–2 s) = back / menu / close
- **Rotate-to-arm + click-to-confirm** = dangerous actions (SOS,
  pairing reset). Deliberate, glove-friendly, hard to trigger
  accidentally.
- Hold during boot = factory reset / pairing mode

**Why a crown over multiple buttons:**
- **Glove-friendly.** Turning a knurled wheel through thick winter
  gloves works; pressing small tactile buttons through them is hit-
  or-miss. Pairs with the MPR121-through-gloves experiment as the
  fallback if cap-touch doesn't work.
- **Eyes-free operation.** Detents are felt, not seen.
- **Single mechanism = many functions** (scroll + click + long-press
  + combos). Fewer holes in the enclosure to seal.
- **Ideal SOS confirmation pattern** (rotate-to-arm + click). Buttons
  can't match this safety property.

**Honest concerns:**
- Mechanical wear (push switch dies first, ~50k clicks typical).
  Sealed Bourns or magnetic variants mitigate.
- Enclosure sealing — shaft = ingress point, needs O-ring.
- Snag risk on harness webbing — mitigated by recessed mount.

### Connectors and ports

- **USB-C** for charging only. PD support not required since
  Mezulla isn't negotiating high-power roles.
- **SMA female** for LoRa antenna (above).
- **18650 cell holder** — snap-in or sled, pilot-accessible
  without tools.
- **GPS active patch antenna** — top-mounted, not on a connector
  (integrated into enclosure design).
- **No header / pin breakouts on the production board.** Dev/debug
  variants can populate them; production should be clean.

---

## Open questions

These are not decided. Future revisions to this document should
resolve them with data.

1. **GPS cold-start at alpine cold** (-10°C to -20°C, 3000m+).
   Datasheet says fine; needs real measurement.
2. **Crash-detection threshold tuning.** How many G's = real crash
   vs hard landing vs fumbled launch run? Tunable in firmware once
   real flight data accumulates; not a v1 launch blocker.
3. **Audio language design.** Specific tones / patterns for each
   event. Captured by experiment 7.
4. **Whether MPR121 capacitive touch works through your gloves.**
   Captured by experiment 4. If yes, could supplement or partially
   replace the crown for specific actions.
5. **Mechanical / enclosure design.** Material (plastic vs aluminum),
   harness mounting style, antenna routing. Heavily dependent on
   antenna-mounting constraint and crown placement.
6. **LFP vs NMC for v2.** Whether the safety advantages of LFP
   justify the capacity loss + charge-circuit redesign.
7. **Sharp Memory LCD vs e-ink** — only relevant if vario-on-
   Mezulla-display becomes a real product goal (currently assuming
   phone-as-vario-display is sufficient).

---

## Pre-design experiments

These should run on existing hardware (LilyGo + SunFounder kit)
before custom-board design commits. Each one resolves an open
question or validates a decision above. Ordered by leverage.

1. **Fly the LilyGo as-is** with stock Meshtastic, mounted on
   your harness, for a real flying day. Records actual battery
   runtime, OLED sunlight readability, physical ergonomics,
   antenna survivability. Largest single information dump
   available.
2. **Antenna position experiment.** Mount the LilyGo at three
   positions (against body / chest strap antenna-up / clipped to
   riser clear of body). Log RSSI from a fixed reference node at
   each. Expected delta: 10–20 dB. Validates the "mounting
   matters more than antenna" decision.
3. **Outdoor visibility of WS2812 LED strip.** Wire the
   SunFounder kit's WS2812 strip to either the LilyGo or the
   SunFounder ESP32. Velcro to harness. Test which colors and
   brightness are readable in midday sun, through sunglasses.
   Decides whether RGB LED status is a useful supplement to the
   main display.
4. **MPR121 capacitive touch through flying gloves.** Wire the
   MPR121 to the SunFounder ESP32. Wear your actual flying gloves
   and try to register touches. Decides whether capacitive input
   is on the table to supplement the crown.
5. **Two-node LoRa range test.** Buy a second LilyGo (~$30) for
   the experiment. Park one at launch, fly with one, log packet
   receipt per altitude / distance / line-of-sight. **Highest-value
   single piece of data** in the whole project — feeds the
   propagation model and validates the buddy-mesh thesis for your
   local flying sites.
6. **Bench battery runtime measurement.** Charge a known cell to
   full, configure the LilyGo to the intended field profile (BLE
   on, OLED auto-sleep, Wi-Fi off), let it run on the bench
   logging timestamps until cutoff. Real number replaces napkin
   math.
7. **Audio language character study.** SunFounder kit buzzer +
   audio amp + speaker. Iterate on what each event should sound
   like (buddy joined / went stale / SOS / low battery) in a
   paragliding cockpit with wind noise. Decides audio language
   for the custom board. **Prototypable now on existing
   hardware.**

**Do not start custom-board design until at least experiments 1,
2, and 5 have run with captured evidence.** Without them the
design is proceeding on assumptions, not data.

---

## Reference: runtime math

Paper-napkin calculations. Replace with measured numbers from
experiment 6 above when available.

**Inputs:**
- 18650 nominal capacity: 3000 mAh (good cell, average grade)
- Practical usable after boost-converter losses + 4.2→3.0 V usable
  range + cold derating: ~2400 mAh

**LilyGo + Meshtastic average draw by mode (estimated):**
| Mode | Avg current | Notes |
|---|---|---|
| Aggressive power-save | ~25 mA | OLED off, BLE off, LoRa periodic TX only |
| Default Meshtastic | ~80 mA | BLE on, LoRa RX continuous, OLED auto-sleep |
| Field flight profile | ~50 mA | OLED disciplined, BLE on for phone, LoRa beacon |
| Wi-Fi enabled (dev only) | ~180 mA | Above + Wi-Fi radio always on |

**Runtime = 2400 mAh ÷ draw:**
| Mode | Runtime |
|---|---|
| Aggressive | ~96 h (~4 days continuous) |
| Default | ~30 h (~1.5 days continuous; week of 4h flying days) |
| Flight profile | ~48 h (~6 full flying days) |
| Wi-Fi on (dev only) | ~13 h (one long flying day) |

**Conclusion:** battery is not the constraint for any single
flying day; weekend XC trips fit one charge; week-long XC trips
fit one charge if Wi-Fi stays off.

**Custom board adds:** ~30–40 mA from GPS + baro + IMU active.
Brings field-flight profile to ~80–90 mA. Still one cell per day
with margin.

**Caveats:**
- Cold weather (0–5°C alpine flying) cuts capacity 15–25%
- Cheap cells deliver 50–70% of labeled capacity (use Tier-A!)
- OLED full-bright continuously adds ~15 mA → cuts default
  runtime from 30 h to ~22 h. With e-ink, this concern goes away.

---

## Reference: cell sourcing quick card

Buy from a reputable distributor (see list above). Verify these
signals before trusting:

- ✅ Datasheet exists and is published
- ✅ Capacity ≤ 3500 mAh for 18650, ≤ 5000 mAh for 21700
- ✅ Continuous discharge claim matches the chemistry (NCR18650B
  is ~3.4 A, not "20 A")
- ✅ Weight ≥ 42 g (18650) / ≥ 65 g (21700)
- ✅ Price in the $5–10 range for genuine Tier-A 18650
- ✅ Brand spelled correctly (counterfeits often have minor typos)

---

## Reference: antenna tuning dimensions

| Dimension | What's at stake |
|---|---|
| Frequency match | Wrong-band antenna → high VSWR, power reflected at radio, PA damage over time |
| Gain (dBi) | More gain extends range in pattern but squashes omni doughnut |
| Polarization | Cross-polarization between TX/RX can cost up to 20 dB (game over) |
| SWR | > 2:1 is problematic; both antenna and radio want 50 Ω |
| **Mounting / environment** | **Human body absorbs ~20 dB at 915 MHz — by far the largest practical loss in wearable form factor** |
| Coax losses | Cheap RG-174: ~1 dB/m at 915 MHz |
| Connector quality | Stock SMA leaks, oxidizes, wears over connect cycles |

**Single most important takeaway:** the mounting decision is the
antenna decision. A great antenna mounted against the pilot's body
loses to a mediocre antenna mounted clear of it.

**Free / Tier-1 actions on existing hardware:**
- Test three mount positions, log RSSI delta
- Verify antenna is roughly vertical in flight posture
- Verify stock antenna is actually 915 MHz, not the wrong band

**Cheap upgrades (~$10–30) if mount test shows antenna is still limit:**
- Quality 915 MHz omni at ~5 dBi from Linx / Pulse / Taoglas
- Half-wave (~16 cm) or 5/8-wave (~20 cm) 915 MHz omni
- Short LMR-100 pigtail to remote-mount the antenna clear of body

**Out of scope:**
- Directional Yagi antennas (defeats omnidirectional buddy-mesh)
- Circular polarization (~3 dB loss vs ideal linear, hard to
  source for 915)
- Active antennas / LNAs (overkill — SX1276 sensitivity already
  world-class)

**Software-side equivalent of an antenna upgrade:** Meshtastic's
spreading factor (SF). SF12 vs SF7 is ~6–9 dB more sensitivity —
equivalent to a 5 dBi antenna upgrade, for free. Worth checking
`meshtastic --info` for the current `lora.modem_preset`.

---

## Reference: documented alternative chip sources

For open-source longevity ([[project-tern-hardware-roadmap-and-openness]]),
every critical chip should have at least one documented alternative
so the BOM survives any single supplier deprecating or withdrawing.

| Function | Primary | Alternative(s) |
|---|---|---|
| LoRa radio | Semtech SX1276 | SX1262 (different matching; v2 upgrade), AI-Thinker Ra-02 modules |
| GPS | ublox NEO-M8N or M10S | Quectel L86/L96, Allystar |
| Barometer | Bosch BMP388 | Infineon DPS310, TDK ICP-10125, TE MS5611 (premium) |
| IMU | TDK ICM-42688 | ST LSM6DSO, ST LSM6DSL (if no vario fusion) |
| Audio amp | MAX98357A (I2S) | PAM8302A (analog input, simpler) |
| Charge IC | TP4056 (current LilyGo) | MCP73831, BQ24074 |

---

## Explicitly out of scope / rejected

These have been considered and consciously excluded. Re-opening
requires new constraints.

- **Integrated power-bank role** (multi-cell pack to top up phone) —
  triples device size, couples failure modes, duplicates a $15
  commodity.
- **USB-C power pass-through** (Mezulla as cable consolidator
  between bank and phone) — pilots' banks already cable straight
  to phone; no value added, only failure surface.
- **LiPo pouch cells** — single-supplier risk, not pilot-swappable,
  worse crash/harness survival, worse open-source story.
- **No-name / "high-capacity-claim" cells** — documented fire risk;
  asymmetric safety stakes when strapped to a pilot.
- **BME688 (humidity+pressure combo)** — pressure noise floor ~4×
  worse than BMP388. Don't trade vario quality for humidity.
- **9-axis IMU with magnetometer** — unreliable heading in
  paragliding cockpit (metal gear, body movement, no field
  calibration possible). Derive heading from GPS.
- **Smart IMU with on-chip fusion (BNO080)** — expensive, less
  control over fusion algorithm, calibrated for VR/robotics not
  vario.
- **Standard OLED in-flight display** — unreadable in direct sun.
- **Standard TFT LCD display** — unreadable in direct sun without
  expensive transflective treatment.
- **3-color e-ink** — refresh ~15 s, too slow.
- **Directional / Yagi antennas** — defeats omnidirectional buddy-
  mesh use case.
- **Custom RF matching network from scratch** — reuse LilyGo /
  Meshtastic reference design; RF redesign is weeks of pain for
  no v1 product gain.
- **Closed-source / proprietary anything in the BOM** — open
  source is non-negotiable per
  [[project-tern-hardware-roadmap-and-openness]].

---

## Related memories and docs

- [[project-tern-hardware-roadmap-and-openness]] — off-the-shelf
  LilyGo now, custom open-source Tern board later
- [[project-tern-hardware-relationship]] — phone app is the
  complete product; hardware is optional enhancement
- [[mezulla-power-architecture]] — single-domain decision
  rationale
- [[tern-hardware-on-hand]] — physical hardware available for
  prototyping
- [[project-tern-human-tests]] — flight-and-conditions validation
  required before any feature is "done"
- [[project-tern-priority-principle]] — don't open intrusive
  feature fronts before existing ones are validated
- [[project-tern-qr-pairing-model]] — board shows QR when
  ownerless; pilot scans with phone camera
- [[project-tern-offline-first]] — every runtime feature must
  work without internet
- `tern-android/scripts/flash-mezulla.sh` — current LilyGo
  flashing pipeline
- `docs/architecture/meshtastic-connection.md` — the abstraction
  Mezulla connects through
- `docs/backlog/current-focus.md` — what we're actually shipping
  now

---

## Maintenance notes

- This is a living document. When experiments produce real
  measurements, update the relevant section with the measured
  value and date.
- When an open question gets answered, move it from "Open
  questions" to "Decisions so far" with the rationale.
- When a decision gets reversed by new data, leave the old
  decision in the doc with a note explaining what changed and why
  — future contributors should be able to see the reasoning trail.
- When this document grows beyond what fits in one file (likely
  around the time real schematics land), split it. Suggested
  splits: `power.md`, `radio.md`, `sensors.md`, `display-audio.md`,
  `mechanical.md`, `sourcing.md`, with this file as the index.
