# Aravis replay — first attempt notes (2026-05-27 evening)

## What we built tonight

Complete pipeline for the Aravis replay test, end to end:

| Layer | Status |
|-------|--------|
| `SwarmPlayback` + speedMultiplier + RandomRangePropagation | Working, unit-tested |
| `MEZULLA_TEST_BUILD` firmware (radio-silent) | Flashed, verified |
| `IgcMockLocationProvider` (DUT GPS injection) | 16 unit tests pass |
| `VirtualPeerInjector` (peer ToRadio over persistent BLE) | 5 unit tests pass, round-trip verified |
| `AravisReplayRunner` (composer) | Compiles, integration tested |
| `AravisReplayTest` (instrumented BDD) | Runs on device |
| `aravis-replay-cycle.sh` (host orchestrator) | Runs, OLED loop + video compose |
| `compose-replay-video.py` (side-by-side video) | ffmpeg pipeline validated dry-run |

## Two attempts tonight

### Attempt 1 — fix(replay) commit `b09716a`

- Test launched while `BleConnection.linkState=DOWN`
- `VirtualPeerInjector` wrote ToRadio packets, but `BleTransport.writeToRadio` returns false when DOWN — silent drops
- BLE eventually came up at 58s, leaving only 12s for peer injection
- Result: `saw []` (zero peers in Redux)
- Bonus bug: cycle script reported `RESULT: PASS` despite test failure (am instrument exit code lies)

### Attempt 2 — fix `375527b` (link UP wait + correct failure detection)

- Test now waits up to 120s for `linkState=UP` before starting runner
- Cycle script parses instrumentation output for real verdict
- Result: `BLE link did not reach UP within 120000ms (currently DOWN)`
- Underlying issue: bond loop — see below

## The bond loop (the actual blocker)

Pattern visible in `/tmp/aravis-debug-2251/ble_logs.txt`:

```
22:50:04: GATT connected → MTU 517 → Discovering services
22:50:15: bond_state → 0 (bond dropped)
22:50:18: BLE scan failed code 1 ALREADY_STARTED
22:50:20: GATT connected → bonding (state=1)
22:50:30: bond_state → 0 (dropped again)
22:50:35: GATT connected → ...
22:50:47: bond_state → 0 ...
```

The bond is being created every ~10s and dropped before reaching state=2 (bonded).
Board is confirmed in NO_PIN mode (`bluetooth.mode: 2`), so bonding shouldn't even
be required — but Android keeps trying anyway. Possibly due to a stale bond record
from earlier FIXED_PIN sessions or some Android Bluetooth stack state we can't
clear via adb without root.

## Morning fix-it plan

**Step 1: Clear the stale bond manually on the phone.**
- Open Android Settings → Bluetooth → Paired devices
- Find `007_6184` (MAC `F0:24:F9:92:61:86`)
- Long-press / settings cog → "Forget" / "Unpair"
- OR: toggle Bluetooth off then on (often clears stuck bond state)

**Step 2: Re-pair from scratch.**
- Run: `./scripts/pairing-test-cycle.sh` from `tern-android/`
- This will: erase board flash → flash test firmware → set identity → pair via QR

**Step 3: Retry the replay.**
- Run: `./scripts/aravis-replay-cycle.sh 10.10.10.82:5555 64`
- Expected output: `tern-android/build/aravis-replay/<timestamp>/{phone-screen.mp4,oled/*.png,composite.mp4}`

**Step 4: If bond loop reappears, investigate:**
- Is the AndroidBleTransport requesting bonding implicitly? Look at GATT
  connection flags — `createBond=true` somewhere?
- Should we use `device.connectGatt(... TRANSPORT_LE, PHY_LE_1M)` with
  explicit no-bond flag?
- Is there a known issue with NimBLE bonding on the ESP32 at high
  connect/disconnect rates?

## Side issue: OLED capture wrote 0 PNGs

The screendump.sh loop runs every 5s during the test, but produced 0 PNGs
both runs. Likely causes:
1. Serial port contention — meshtastic Python lib opens `/dev/ttyACM0` while
   the firmware is logging to it. Maybe inits too slowly.
2. The `screendump.sh` script's positional-args fix may have a subtle bug.

Quick check: run `bash /home/raghu/src/meshtastic-firmware/scripts/screendump.sh /dev/ttyACM0 /tmp/test.png`
manually and see if it produces a PNG. If yes, the cycle loop issue is timing-related.

## Files reference

- Logs: `/tmp/aravis-debug-2251/`
- Test outputs: `tern-android/build/aravis-replay/20260527-22*`
- Test source: `tern-android/app/src/instrumentedTests/kotlin/com/ternparagliding/test/AravisReplayTest.kt`
- Cycle script: `tern-android/scripts/aravis-replay-cycle.sh`

## What's verifiably true after tonight

- All Kotlin compiles, 365+ unit tests pass
- Pairing works end-to-end (verified earlier)
- Persistent BLE connection works in isolation (verified earlier)
- The replay pipeline is wired correctly; the only blocker is the BLE bond
  stability under the test harness's force-stop + reconnect pattern
- Once BLE link is UP, the rest should work — `VirtualPeerInjector` round-trip
  is verified in unit tests
