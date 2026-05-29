# MCU Selection

Back to [index](custom-mezulla-design-reference.md).

---

## Decision: ESP32-S3 for v1; nRF54LM20A as future upgrade path

- **v1 default:** **ESP32-S3** (Espressif). Dual-core Xtensa @ 240 MHz,
  512 KB SRAM, WiFi + BLE 5.0, native USB.
- **Future upgrade path:** **Nordic nRF54LM20A.** Cortex-M33 128 MHz +
  RISC-V 128 MHz coprocessor, 512 KB RAM, 2 MB NVM, BLE 6.0, high-speed
  USB, up to 66 GPIOs, 0.9 uA sleep. 22 nm process. Production Q1 2026.
- **Evaluated and passed on for v1:** **nRF52840**, **nRF5340**,
  **nRF54L15** (see evaluations below).

---

## Why ESP32-S3 for v1

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

---

## Why nRF54LM20A as the future path

The nRF54LM20A is Nordic's newest nRF54L-series chip (announced
Sep 2025, production Q1 2026). It fixes every objection raised
against earlier Nordic options:

- **512 KB RAM** — matches ESP32-S3. Meshtastic + IMU sampling +
  Kalman filter + audio generation fits without firmware optimization
  heroics. This was the hard blocker on nRF52840 and nRF54L15 (both
  256 KB).
- **USB is back** — high-speed USB, so flashing and debugging via
  cable works. nRF54L15 dropped USB entirely; this restores it.
- **128 MHz Cortex-M33 + 128 MHz RISC-V** — the RISC-V handles
  time-critical background tasks (sensor sampling, radio timing)
  while M33 runs application logic. Architecturally cleaner than
  ESP32's two identical cores or nRF5340's asymmetric dual-M33.
- **0.9 uA sleep with RTC** — an order of magnitude better than
  ESP32-S3 (~10 uA). Always-on beacon mode becomes realistic for
  days or weeks on a single 18650.
- **66 GPIOs** — more than enough for every Mezulla peripheral with
  room to spare.
- **BLE 6.0 with Channel Sounding** — precision ranging could enable
  close-proximity buddy detection in future.
- **2 MB NVM** — room for Meshtastic firmware + flight data logging.
- **22 nm FD-SOI process** — 30-50% lower active power than nRF52
  series (40 nm).

**What it doesn't have:**
- **No LoRa** — needs external SX1276/SX1262, same as any Nordic
  chip. Not a dealbreaker, just a discrete component on the board.
- **No WiFi** — loses the TCP dev workflow. Can pair with nRF70
  companion IC if needed, but that's extra BOM.
- **No Meshtastic support yet.** nRF54L15 has one board proposal
  (ME25LS02). nRF54LM20A has nothing. The silicon is brand new.

**When to revisit:**
- When the product is stable on ESP32-S3 and power optimization
  becomes the interesting problem.
- When Meshtastic lands nRF54L-series support with real field use.
- Not before both conditions are true.

---

## Comparison table

| | ESP32-S3 | nRF52840 | nRF5340 | nRF54L15 | **nRF54LM20A** |
|---|---|---|---|---|---|
| Core | 2x Xtensa 240 MHz | 1x M4F 64 MHz | M33 128 + M33 64 MHz | M33 128 + RISC-V | **M33 128 + RISC-V 128 MHz** |
| RAM | 512 KB | 256 KB | 512+64 KB | 256 KB | **512 KB** |
| Storage | — | 1 MB flash | 1.25 MB flash | 1.5 MB RRAM | **2 MB NVM** |
| BLE | 5.0 | 5.0 | 5.3 | 6.0 | **6.0** |
| WiFi | Yes | No | No | No | No |
| Sleep (w/ RTC) | ~10 uA | ~2 uA | ~1.5 uA | sub-1 uA | **0.9 uA** |
| USB | Native | Native | Native | No | **High-speed** |
| GPIO | — | 48 | 48 | — | **up to 66** |
| Process | 40 nm | 40 nm | 40 nm | 22 nm | **22 nm** |
| Meshtastic | Primary | Primary | Early | Proposed | **None yet** |
| FPU | Yes | Yes | Yes | Yes | Yes |

---

## Nordic chips evaluated and passed on

### nRF52840

Single-core ARM Cortex-M4F @ 64 MHz, 256 KB RAM. Peripheral buses
(4x SPI, 2x I2C, 2x UART, I2S, hardware QDEC for the crown encoder)
map cleanly to Mezulla's sensor load, but stacking Meshtastic +
100-200 Hz IMU sampling + Kalman-filter vario fusion + audio tone
generation on a single 64 MHz core with 256 KB RAM makes it a firmware
optimization project, not a product project. Primary Meshtastic
platform — no ecosystem risk, just not enough headroom.

### nRF5340

Dual-core (128 MHz app + 64 MHz network), 512 KB + 64 KB RAM, BLE 5.3.
Solves the CPU/RAM problem. Was the previous future-path candidate,
but **superseded by nRF54LM20A** which delivers the same RAM, faster
cores, dramatically better power (0.9 uA vs 1.5 uA sleep), USB,
more GPIOs, BLE 6.0, and newer process node — all in a single die
instead of dual-die. nRF5340 remains a fallback if nRF54LM20A
availability slips, but it's no longer the primary future target.

### nRF54L15

Cortex-M33 128 MHz + RISC-V, BLE 6.0, 22 nm, sub-1 uA sleep.
Impressive power numbers but **only 256 KB RAM** — same constraint
as nRF52840. No USB (worse dev experience). One Meshtastic board
proposal exists (ME25LS02 with LLCC68). Worth watching if someone
proves Meshtastic + full sensor stack fits in 256 KB, but the
nRF54LM20A removes the need to squeeze.

---

## Other MCUs evaluated

- **STM32WL** (ST): has LoRa radio built into the silicon (SX126x IP),
  but no integrated BLE — needs external BLE module. Meshtastic
  support is experimental/community-maintained. Interesting for a
  future single-chip design, not v1.
- **RP2040** (Raspberry Pi): no built-in wireless at all. Needs
  external modules for both BLE and LoRa. Experimental Meshtastic
  support. Not a fit.
- **CC1352** (TI): Sub-GHz + BLE combo. Limited Meshtastic support.
  Not worth the ecosystem switch.
- **WLR089U0** (Microchip): SAM R34 Cortex-M0+ with integrated LoRa.
  Targets LoRaWAN (star topology with carrier base stations), not
  Meshtastic mesh. Wrong protocol, wrong ecosystem, tiny community.

---

## Peripheral mapping on nRF52840 (reference)

Included for reference since nRF52840 was seriously evaluated.
All Mezulla peripherals do map to available buses — the constraint
is CPU/RAM, not pin count.

| Mezulla function | Bus | nRF52840 instance |
|---|---|---|
| LoRa (SX1262) | SPI | SPIM3 (dedicated) |
| E-ink display | SPI | SPIM2 |
| BMP388 baro | I2C | TWIM0 (shared bus) |
| ICM-42688 IMU | I2C | TWIM0 (different address) |
| GPS (ublox) | UART | UARTE0 |
| Audio amp (MAX98357A) | I2S | I2S0 |
| Crown encoder | HW decoder | QDEC (built-in) |
| USB-C | Native | USB |
