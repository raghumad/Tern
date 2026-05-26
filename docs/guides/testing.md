# Testing Guide

## Unit Tests

304 tests, JUnit 5.

```bash
./gradlew :app:testDebugUnitTest
```

## Instrumented Tests

Two ways to run:

```bash
# Managed device (Pixel 9 Pro API 35 AOSP — Gradle provisions it)
./gradlew pixel9proapi35DebugAndroidTest

# Local emulator or connected device
./gradlew connectedDebugAndroidTest
```

## BDD Framework

Most instrumented tests extend `MapVisualTest`, which provides `given`/`when`/`then`/`scenario` helpers. One test (`BaseUITest` subclass) uses `BddTest` instead. Both base classes handle screenshot capture and screen recording automatically.

### Screen Recording

Always-on via `VideoHelper`. Recording starts in `@Before`, stops in `@After`. Videos land at `/sdcard/tern-tests/`. Pull them with:

```bash
adb pull /sdcard/tern-tests/
```

### Screenshots

`ReportGenerator.captureScreenshot()` fires at each BDD step. Captured images go through `VisualValidator`, which checks for blank frames and compares against a blacklist of known-bad states.

## @Liar Annotation

Marks tests whose Gherkin steps read correctly but whose assertion bodies were gutted. There are 10 `@Liar` methods across 6 files with 30 TODO placeholders. The Gherkin is valuable (it documents intended behavior); the assertions need to be rebuilt.

## Consolidated Dashboard

```bash
# Run from tern-android/
python3 scripts/test_report.py
```

Aggregates JUnit XML results, screenshots, and videos into a single HTML report at `app/build/reports/tern-test-dashboard.html`.

## Visual Reviewer

```bash
python3 scripts/visual_reviewer.py
```

Generates per-test HTML reports with approve/reject buttons for golden screenshots:
- **Approve**: copies the screenshot to `assets/goldens/`. Future tests fail if they diverge from this state.
- **Reject**: adds the screenshot hash to `blacklist.json`. Future tests fail if they reproduce this bad state.

## Convergence Test

The buddy-flying pipeline has two layers:

- `BuddyFlyingSmokeTest` — unit test, gold standard. Validates the mesh event flow end-to-end with `StubMeshtasticConnection`.
- `MezullaBuddyFlyingVisualTest` — instrumented test running the same pipeline on an emulator using `SwarmSimulatedConnection`, with screenshots at each BDD step.

## Reliability Techniques

### Cache Clearing
`MapVisualTest` proactively deletes disk caches (map tiles, airspace) before the activity launches to avoid race conditions from stale state.

### Log Synchronization
`ReportGenerator.waitForLogMatching(tag, regex)` blocks until a specific internal state is logged. Needed for async flows like airspace rendering where `waitForIdle()` is insufficient.

### Permission Handling
`GrantPermissionRule` in `MapVisualTest` grants location permissions before the Compose hierarchy starts. This avoids activity restarts on API 35 that happen when permissions are granted after `setContent`.

## Test Truthfulness Rules

Two principles that prevent dishonest tests:

**Assert downstream, not upstream.** If the test dispatches to Redux,
don't assert against Redux — assert against what the pilot actually
sees. Check the MapLibre camera position, analyze screenshot pixels,
or query the Compose semantics tree. Checking the store you just wrote
to is tautological and will pass even when the feature is broken.

**One of everything.** One map, one camera, one state. If you can't add
a layer to the existing map, that's a bug to fix — not a reason to
create a second map. Any parallel rendering path that bypasses what
the pilot sees will produce passing tests and a broken app.

## Known Issues

- **Two-map bug.** `NativeMapView` creates a second MapView for peer
  markers (because maplibre-compose SymbolLayer was broken in v0.13.0).
  This second map doesn't sync with Redux — its camera is stuck at
  initial position. Root cause of many test/screenshot mismatches.
  Fix: add peer layers to the compose map's underlying MapView, delete
  the second one.
- `BaseUITest` has an `@Ignore` that could silently skip `BddTest` subclasses.
- `MapTestHelper` is dead OSMDroid-era code. Should be deleted.

## Output Files

- `app/build/reports/tern-test-dashboard.html` — consolidated dashboard
- `/sdcard/tern-tests/` — screen recordings (on device)
