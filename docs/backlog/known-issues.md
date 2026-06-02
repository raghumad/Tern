# Known issues

App-cleanup issues that are known and parked while the focus is on
mezulla/LoRa. This is **not** an active to-do list — it's the starting
point for when focus shifts back to app cleanup.

For current test pass/fail counts, run `./gradlew testAll` (or
`python3 scripts/test_report.py` from `tern-android/`) — counts are not
pinned here because they go stale. Historical clean-build snapshots from
May 2026 are archived in
[../archive/known-issues-snapshots-2026-05.md](../archive/known-issues-snapshots-2026-05.md).

## Get-well program

Issues that need systematic fixing. Not blocking mezulla work but tracked
so they don't get forgotten.

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
stitching), or accept video-only-on-real-device. (Real-hardware cycle
tests record fine via `screenrecord` on the physical phone.)

### MapTestHelper gesture methods dead

All 6 gesture methods use OSMDroid MapView projection. Tests that
used them were rewritten to dispatch via Redux (which is tautological
but at least doesn't crash). Real gesture testing needs MapLibre
coordinate projection — which IS now available via
`cameraState.projection.screenLocationFromPosition` /
`positionFromScreenLocation` (used by `OffScreenPeerIndicators`). These
helpers could be rewritten against it instead of OSMDroid.
