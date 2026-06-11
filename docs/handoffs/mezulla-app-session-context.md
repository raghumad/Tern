# Mezulla App — rebuilt session context (2026-06-01)

Reconstructed from the crashed "Mezulla App" chat (session `53cf0a62`, May 26 → Jun 1,
22k lines). The original VSCode session crashed on resume because it was stuck registered
as a background agent (pid 111347) — not a data problem. This doc is the carry-forward so
we can continue in a fresh chat.

## Where we are (clean checkpoint)

- Branch: **`mezulla`** @ `fffb3a4` (PR#20 merged 2026-06-01 15:09 UTC).
- PR#20 = **"fix(mezulla): reliable BLE link + drop recovery, end-to-end."** Shipped the
  self-healing BLE link, heartbeat lifecycle, process-scoped connection manager, and the
  BleReliabilityTest harness (T1–T8 / F1–F6). Validated on real hardware.
- Branch tree is the canonical three: `main` → `revive-the-droid` → `mezulla`. Merged
  feature branches (`aravis-peer-render-fix`, `fix-tests-and-scrub`) were deleted; worktree
  removed. Working tree clean.

## North star

Two pilots, two Mezulla boards: Tern receives peer messages via Mezulla (BLE → LoRa mesh)
and the pilot **sees buddies on the map in real time**. The validated end-to-end scenario
is **Edith's Gap** (replays real flight logs from `~/Downloads/Ediths's Gap`, 2 buddies,
~30 min). Safety rule from the user, repeated and non-negotiable:

> "if mezulla is with the pilot during flight it will be in ble range for the tern app.
> that means we cannot lose ble connectivity." BLE reliability is the **highest priority**.

## Definition of Done (workflow rule)

A feature is DONE only when: (1) the BDD test passes, **and** (2) user reviews the test
code, **and** (3) user reviews the BDD report (with embedded video). Only then → push.
All BLE-related actions are pre-approved. Never `git push` without explicit ask.

## Key technical state (what's solved)

- **BLE phone-protocol handshake**: Meshtastic 2-stage handshake — heartbeat +
  `want_config_id` 69420 (CONFIG_NONCE) then 69421 (NODE_INFO_NONCE), detect
  `config_complete_id`. Emit `LinkState.UP` after **Stage 1** (don't wait for Stage 2's
  optional nodeDB) so `PairingOrchestrator`'s LINK_UP timeout never fires.
- **Drain-on-write-ack** (the breakthrough): drain FromRadio inside
  `onCharacteristicWrite` when the TO_RADIO write succeeds — NOT inline after
  `writeToRadio` (Android GATT serializes; inline drain returned false). This made real
  LoRa peer events flow.
- **Self-healing reconnect**: `AndroidBleTransport` releases the scan latch on a hit (was
  one-way → never recovered after first drop). `BleConnection.stop()` cancels heartbeat +
  handshake waiters and uses a connection epoch so a superseded handshake can't drive the
  link back UP. Manager + orchestrator hoisted to process-scoped `TernApplication` so the
  link survives Activity recreation.
- **Silent BLE pair**: NO_PIN when unclaimed; the QR token is the auth. No spec deviation
  for Android ≥12; documented that we don't use BLE encryption (transparency to user).
- **Peer rendering**: canonical maplibre-compose pattern — ONE GeoJsonSource + ONE Layer
  (not forEach-per-peer, which silently dropped layers). `@MaplibreComposable`.
- **Test report**: per-test BDD HTML, embedded screen-recording video below the
  Performance Scorecard, dual-write to TestStorage + getExternalFilesDir, adb-pull in the
  gradle task. Removed the approve/reject golden buttons.

## BleReliabilityTest map (T1–T8 Tern-side, F1–F6 firmware-side)

Per PR#20, the reliability core is **green**: T2 (reconnect-after-reboot), T4 (MTU),
T6 (heartbeat), T7 (badge), plus Edith's Gap end-to-end incl. peer render.

Open / deferred (the real backlog):
- **GATT-133 retry in `BlePairingService`** — harden pairing against the transient
  GATT error 133 on first connect. (Named follow-up, not in PR#20.)
- **T3 peer-event THEN + F5 PHY** — red only because the test env has a single mesh node;
  need a second node online. F5 also reads PHY *before* the 2M upgrade negotiates (test
  sequencing).
- **@Ignore, deferred**: T1, T5 (need foreground service + wakelock — architectural),
  T8 (second pairUri), F1–F4, F6 (firmware-side change or host sidecar harness).

## Hardware notes

- **Mezulla 007** = LilyGo T3 V1.6.1 (ESP32-PICO-D4, SX1276, SSD1306), node `!4a312aaa`,
  BLE `007_6184`, MAC `F0:24:F9:92:61:86`. Primary board.
- **Mezulla 008** = second-hand Heltec V3 — OLED has real **burn-in** (4-day continuous
  use); QR not camera-readable. Use adb-intent pairing path to bypass QR. LoRa TX on one
  board got fried at some point → plan is **Heltec = receiver, T3 = transmitter/buddy**.
- Test phone: **Ulefone Power Armor 14 Pro** (Android 12), USB serial `3097TH1010003244`
  (Wi-Fi adb `10.10.10.82:5555`). Daily phone: Pixel 10 Pro.
- Reset/flash + deeplink scripts live in `~/src/meshtastic-firmware/scripts/`; current
  deep link in `docs/handoffs/mezulla-deeplink.txt`. User owns firmware too now (the
  separate firmware agent was retired).

## Gotchas (re-grant / re-enable after destructive adb)

- `pm clear` / `pm clear`-style wipes drop Tern's runtime permissions — re-grant
  BLUETOOTH_SCAN/CONNECT + ACCESS_FINE_LOCATION after every clear.
- adb `svc bluetooth disable` can turn phone BT off; re-enable with `svc bluetooth enable`
  (throws a cosmetic NPE but works).
- For the USB phone, pass `-PdeviceSerial=3097TH1010003244` (default was the Wi-Fi adb).

## Likely next step

Pick one: (a) **GATT-133 retry** in `BlePairingService` (production reliability), or
(b) **bring a second mesh node online** so T3 peer-event + F5 PHY go green, or
(c) move up the @Ignore stack (foreground service + wakelock for T1/T5 — also a real
in-flight safety need: keep the BLE link alive with the screen off).
