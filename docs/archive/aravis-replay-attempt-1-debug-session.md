# Aravis replay — late-night session debrief (2026-05-27 → 2026-05-28 ~00:00)

> **ARCHIVED / RESOLVED.** Historical debug debrief. The "final blocker"
> below (duplicate concurrent `BleConnection`s) was found and fixed; the
> reliable self-healing BLE link shipped in PR #20. The Aravis replay now
> passes end-to-end on real hardware (`aravisCycleTest`), alongside Edith's
> Gap and Bir Billing, via the unified `MezullaPeerCycleTest` harness.
> Kept for history only.

## Bottom line

Four end-to-end attempts. Pipeline fully built, multiple real bugs found
and fixed. Final blocker is downstream of the BLE link — the link IS
reaching UP now, but injected ToRadio writes don't round-trip back as
peer events. Likely **duplicate concurrent BleConnections** (two GATT
callbacks fire on different TIDs in the same PID).

## What we built tonight

| Layer | Status |
|-------|--------|
| `SwarmPlayback`, `SwarmSimulatedConnection`, speedMultiplier | Done, unit-tested |
| `RandomRangePropagation` (chaos) | Done, unit-tested |
| `MEZULLA_TEST_BUILD` firmware (radio-silent) | Done |
| `IgcMockLocationProvider` (DUT GPS injection) | 16 unit tests pass |
| `VirtualPeerInjector` (peer ToRadio over persistent BLE) | 5 unit tests pass |
| `AravisReplayRunner` (composer) | Done |
| `AravisReplayTest` (instrumented BDD) | Runs, hits final blocker |
| `aravis-replay-cycle.sh` (host orchestrator) | Done |
| `compose-replay-video.py` (side-by-side video) | Done, dry-run validated |

## Bugs found and fixed tonight

1. **`FIXED_PIN` after claim caused infinite bond loop** — fixed by
   keeping board in NO_PIN forever. Firmware commit `d560687`.
   The QR token IS the authentication; default FIXED_PIN (123456) was
   never real security.

2. **Mock location appops not granted** — fixed by `adb shell appops
   set ... android:mock_location allow` for both Tern and test APKs.
   No manual Developer Options step needed.

3. **Stale Android BLE bond from FIXED_PIN era** — fixed by toggling
   Bluetooth off/on via `adb shell svc bluetooth disable/enable` (NPE
   stack traces in stderr are non-fatal Android 12 noise).

4. **Phone screen off → Android 12 throttles BLE scan to nothing** —
   verified `mScreenOn=true` before pairing. The cycle scripts already
   wake the screen.

5. **EstablishingLink timeout too short** — bumped from 30s to 90s in
   BlePairingTest; AravisReplayTest waits 120s for link UP.

6. **am instrument exits 0 on test failure** — cycle script now parses
   instrumentation log for `FAILURES!!!` / `OK (N tests)` instead of
   trusting exit code.

7. **GATT race: drainFromRadio fired during pending descriptor write**
   — fixed by moving the first drain into `onDescriptorWrite` filtered
   to FROM_NUM. Commit `dc0e678`. Verified via diagnostic logs in
   commit `4bcd037`:
   ```
   onDescriptorWrite: uuid=ed9da18c-... (FROM_NUM) status=0
   drainFromRadio: readCharacteristic returned true
   handleFromRadioRead: 0 bytes, connected=false
   FIFO drained — emitting Connected
   ```

## What's still broken

**After all four attempts, the test fails with `saw []` — 0 peers in
Redux.** The BLE link is verified UP. But peer Position frames injected
via `VirtualPeerInjector` → `BleConnection.injectRawToRadio` →
`BleTransport.writeToRadio` apparently don't round-trip back as
PeerPositionUpdate events.

The smoking gun: in every test run, the logs show duplicate
`AndroidBleTransport: GATT connected` events at the same time, on
DIFFERENT thread IDs within the same PID:

```
22459 22471 I AndroidBleTransport: GATT connected ... TID 22471
22459 22547 I AndroidBleTransport: GATT connected ... TID 22547
```

Hypothesis: Two `BleConnection` instances are racing. The test's
`activity.connectionManager.activeBleConnection()` returns one of them.
`VirtualPeerInjector` writes ToRadio to that one. But the board's
FromRadio response comes back on the OTHER connection's
`onCharacteristicChanged`. Neither connection sees the full round-trip.

## Morning fix-it plan

### Step 1: Confirm the duplicate-connection hypothesis

Add a connection-identity log to `BleConnection.injectRawToRadio` and
`BleConnection.handleTransportEvent`:
```kotlin
Log.i(TAG, "[BleConnection@${System.identityHashCode(this)}] injectRawToRadio: ${bytes.size} bytes")
Log.i(TAG, "[BleConnection@${System.identityHashCode(this)}] received event $event")
```
Run the test. If we see different `BleConnection@xxx` IDs for inject vs
event, hypothesis confirmed.

### Step 2: Find where the second connection comes from

Possibilities to investigate:
- `MezullaConnectionManager.startConnection()` racing with itself if
  fired twice in rapid succession (e.g., onCreate + the
  PairingState observer)
- The instrumented test creating its own activity instance while the
  pre-existing app process still has one
- `AndroidBleTransport.start()` being called twice somehow

### Step 3: Deduplicate

Once the source is found, gate it. Likely a simple `if (activeMac ==
mac && activeConnection != null) return` check that's currently being
bypassed by some race.

### Step 4: Re-run aravis-replay-cycle.sh

If the connection is single, the round-trip should work and peers
should land in Redux within 60s.

## Files reference

- Final logs: `tern-android/build/aravis-replay/20260527-234918/`
- Diagnostic patch: `dc0e678` (GATT race fix) + `4bcd037` (diagnostic logs)
- Old debug bundle: `/tmp/aravis-debug-2251/`

## What's verifiably true

- Board flashed, paired (node `42ad0e6d`), persistent BLE in NO_PIN mode
- Pairing flow works end-to-end (verified by EstablishingLink commit's smoke test earlier today)
- BLE link reaches UP cleanly with GATT race fix (verified by `FIFO drained` log)
- All 365+ unit tests pass
- `VirtualPeerInjector` round-trip works in unit tests (encodes ToRadio → decodes as FromRadio → produces PeerPositionUpdate with correct node number)
- The duplicate-connection theory is the only remaining unverified piece

The Aravis replay is one bug away from working end-to-end. Sleep, then
add the identity log, see which BleConnection instance is which, fix.
