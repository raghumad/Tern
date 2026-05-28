# Known issues

A snapshot of failures observed on a clean build, kept here so we don't
lose track of regressions while focus is elsewhere. When focus shifts
from LoRa (mezulla) back to app cleanup, this is the starting list.

This file is **not** a to-do for now. We are not actively fixing
these. They are known and parked.

## Snapshot: 2026-05-26 — post-scrub + single-map + package rename

Unit tests: 325 passed, 0 failed. Instrumented: 26 passed, 23 failed,
3 skipped, 19 @Liar. Package renamed to `com.ternparagliding`.

## Get-well program

Issues that need systematic fixing. Not blocking mezulla work but
tracked so they don't get forgotten.

### Airspaces and PG spots never render in test screenshots

Every instrumented test screenshot shows an empty map — no airspace
polygons, no PG spot markers. Root cause: `MapVisualTest.@Before`
sets `CountryUtils.setTestCountryCode("TEST")` and clears all caches.
Tests that call `givenAppIsLaunchedOnMap(countryCode = "us")` reset
the country code but the download + cache + index pipeline takes too
long, or never triggers, or times out silently.

The test infrastructure needs a reliable way to inject pre-built
airspace/PGSpot FlexBuffer data into the cache before the test runs
so the overlay has something to render. `TestCacheInjector` exists
for this but most tests don't use it.

Tests affected: any test that claims to verify overlay rendering
(AirspaceUXTest, DeclutteringUXTest, DenseClusterDeclutteringTest,
SettingsScreenTest.testLayerToggles, ResourceAuditTest, etc.)

### screenrecord doesn't work on ATD managed device images

`VideoHelper` starts `screenrecord` but AOSP ATD emulator images
lack GPU-backed screen capture. Files are empty/missing. Options:
use a non-ATD image (heavier), use FrameCaptureHelper (screenshot
stitching), or accept video-only-on-real-device.

### MapTestHelper gesture methods dead

All 6 gesture methods use OSMDroid MapView projection. Tests that
used them were rewritten to dispatch via Redux (which is tautological
but at least doesn't crash). Real gesture testing requires MapLibre
coordinate projection, which maplibre-compose doesn't expose yet.

## Snapshot: 2026-05-25 — post-scrub on `fix-tests-and-scrub` branch

Unit tests: 338 passed, 0 failed (after PeerFeatureCollectionTest and
PeerReducerTest fixes). Instrumented tests not run in this snapshot
(no emulator).

5 instrumented tests tagged `@Untruthful` — they pass but their
assertions don't match their BDD step names. See test infrastructure
section in current-focus.md for details.

New consolidated dashboard: `python3 scripts/test_report.py` from the
`tern-android/` directory.

---

## Snapshot: 2026-05-23 — fresh-machine clean build

Build target: `./gradlew testAll` on `mezulla` (4 commits ahead of
`revive-the-droid`).

**Build infrastructure works.** Dependencies resolved, Kotlin compiled
with zero errors, managed-device emulator (Pixel 9 Pro / API 35 / AOSP)
booted and ran tests, coverage report generated. Wall time: 1h 33m.

### Unit tests

```
Total: 115 (Expected: ~116)
Passed: 115
Failed: 0
Skipped: 0
```

⚠️ The test-counting harness flagged a "potential test runner crash" —
expected 116 tests from source, only 115 reported. One test is silently
missing. Worth understanding before this snapshot can be called clean
even on the unit side.

### Instrumented tests (managed Pixel 9 Pro / API 35)

```
Total: 54
Passed: 35
Failed: 19
Skipped: 0
Overall instruction coverage: 18.2%
```

### Failing instrumented tests (19)

Grouped roughly by feature area:

**Route planning / management**
- `MapInteractionTest.testMapLongPressCreatesRoute`
- `RouteManagementTest.testReorderWaypoints`
- `RouteManagementTest.testShareRoute`
- `FAITaskUITest.testCreateCompleteCompetitionTask`
- `FAITaskUITest.testChangeWaypointTypeToSpeedSection`
- `FAITaskUITest.testConfigureFAITaskParameters`
- `AviationRoutePlanningTest.pilot_plans_mountain_record_attempt`
- `AviationRoutePlanningTest.pilot_adapts_route_to_weather_risk`
- `AviationRoutePlanningTest.pilot_imports_random_competition_task`
- `BirBillingCompetitionTest.pilot_flies_bir_billing_himalayan_odyssey`

**Hazards / overlays / map rendering (RFC005)**
- `ResourceAuditTest.auditHazardVisualFidelity`
- `UnifiedLocationTest.testHazardVisualizationRFC005`
- `UnifiedLocationTest.testLocationMarkerRFC005Compliance`
- `DenseClusterDeclutteringTest.testZoomToRouteAutomaticDecluttering`
- `DynamicMarkerTest.testPGSpotMarkerSwitchesToWindGauge`
- `PGSpotInteractionTest.pilot_scans_mountain_launch_for_safe_flying_conditions`

**Weather UX**
- `RouteDetailPanelWeatherTest.testWaypointWeatherDisplay`
- `WeatherUXTest.pilot_verifies_realtime_weather_at_lookout_mountain`
- `WeatherUXTest.pilot_identifies_atmospheric_hazards_in_alpine_terrain`

### What this means

The failure pattern matches the user's prior framing: route planning
barely works and doesn't render accurately; airspace / hazard / marker
rendering has performance and correctness gaps. These are the
regressions that need addressing during the eventual app-cleanup focus.

### Reports

If anyone wants to look at the actual HTML test reports (they're not in
the repo — they're under build/ and gitignored), the build that
produced this snapshot wrote them to:

- `tern-android/app/build/reports/androidTests/managedDevice/debug/allDevices/index.html`
- `tern-android/app/build/reports/bdd-report/`
- `tern-android/app/build/reports/jacoco/jacocoTestReport/html/index.html`

These will be overwritten by the next build.
