# MCU Selection

Back to [index](custom-mezulla-design-reference.md).

---

## Decision: ESP32-S3 for v1; nRF5340 as future upgrade path

- **v1 default:** **ESP32-S3** (Espressif). Dual-core Xtensa @ 240 MHz,
  512 KB SRAM, WiFi + BLE 5.0, native USB.
- **Future upgrade path:** **nRF5340** (Nordic). Dual-core ARM
  (128 MHz app + 64 MHz network), 512 KB RAM, BLE 5.3, no WiFi.
  Superior power efficiency (~1.5 uA sleep vs ~10 uA on ESP32).
- **Evaluated and passed on for v1:** **nRF52840** (Nordic). Single-core
  ARM Cortex-M4F @ 64 MHz, 256 KB RAM. Peripheral buses (4x SPI,
  2x I2C, 2x UART, I2S, hardware QDEC for the crown encoder) map
  cleanly to Mezulla's sensor load, but stacking Meshtastic + 100-200 Hz
  IMU sampling + Kalman-filter vario fusion + audio tone generation on a
  single 64 MHz core with 256 KB RAM makes it a firmware optimization
  project, not a product project.

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

## Why nRF5340 (not nRF52840) as the future path

nRF52840's single 64 MHz core is tight for Mezulla's full sensor
stack. The nRF5340 solves this with a dedicated 64 MHz network core
(runs BLE autonomously) plus a 128 MHz application core with
512 KB RAM — closing the gap with ESP32-S3 while delivering
Nordic's power advantage.

Meshtastic nRF5340 support is early but growing.

**When to revisit:**
- When the product is stable and power optimization becomes the
  interesting problem (always-on beacon mode, multi-day standby).
- When Meshtastic nRF5340 support matures to first-class.
- Not before both conditions are true.

---

## Comparison table

| | ESP32-S3 | nRF52840 | nRF5340 |
|---|---|---|---|
| Core | 2x Xtensa 240 MHz | 1x Cortex-M4F 64 MHz | 128 MHz app + 64 MHz net |
| RAM | 512 KB | 256 KB | 512 KB |
| BLE | 5.0 | 5.0 | 5.3 |
| WiFi | Yes | No | No |
| Sleep current | ~10 uA | ~1.5 uA | ~1.5 uA |
| USB | Native | Native | Native |
| Meshtastic | Primary | Primary | Early |
| FPU | Yes | Yes | Yes |

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
