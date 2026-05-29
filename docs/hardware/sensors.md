# Sensors: GPS, Barometer, IMU

Back to [index](custom-mezulla-design-reference.md).

---

## GPS: integrated, ublox M8N or M10S + active patch antenna

- **Chip primary:** ublox M8N or M10S — multi-constellation (GPS +
  GLONASS + Galileo), aviation-grade, well-supported in community
  firmware. M10S is newer with lower power and integrated-antenna
  package option (SAM-M10Q).
- **Active patch antenna** (~25 x 25 mm) on top of the enclosure
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

**Open question:** cold-weather (-10 C to -20 C) GPS cold-start time
at alpine altitude. Datasheet says fine; real measurement is honest.

---

## Barometer: BMP388 primary; DPS310 as documented alternative

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
adds humidity + gas but its pressure noise floor is ~4x worse than
BMP388. Don't trade vario quality for humidity. If humidity is ever
needed, add a separate **Sensirion SHT40** ($2) mounted where it
gets airflow.

**What baro unlocks:** local vario computation, atmospheric pressure
logging, altitude data independent of GPS (canyon flying). Combined
with IMU (below), enables thermal detection and gust-rejected vario
fusion.

---

## IMU: ICM-42688 (vario-grade gyro for baro+IMU fusion)

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
1. **Crash detection.** Hard-impact accel spike -> immediate auto-SOS
   broadcast over LoRa with last-known GPS. Lifesaving in remote
   terrain.
2. **Motion state classification.** Stationary / walking / flying.
   Lets Mezulla adjust beacon rate dynamically (slow on ground,
   fast in air). Battery saving + better data when it matters.
3. **Pre/post-flight detection.** Auto-start logging on motion +
   altitude change; auto-stop on landing. Removes pilot ceremony.
4. **Baro+IMU vario fusion.** See below — this is the gyro
   investment justification.

---

## Why vario fusion matters (the gyro investment)

Pure baro vario is jumpy in turbulence and reports thermal entry
with delay. Baro+IMU fusion (well-established in sailplane
competition varios — LX, Naviter, Skytraxx) gives a cleaner, faster
vario signal: subtracts out gust artifacts, detects real lift 1-3 s
faster than baro alone, smooths turbulence noise. For a buddy-mesh
context, the killer feature is broadcasting **"Luc is +2.8 m/s in
a core at this position"** over LoRa — far more actionable than just
position. Reference projects: XCSoar, Open Variometer Project,
Kobo-vario builds.
