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

**Mechanism nailed down (2026-06, PG-spot overlay restore).** There are
two distinct ways injection silently yields an empty overlay, found while
verifying the restored PG-spot layer:

1. **Integrity size floor.** `SpatialDiskCache.validateCacheIntegrity`
   rejects a region whose `.flex` is `< 100 B` (or `.idx < 50 B`). A
   single injected feature serialises below that, so `isCached` returns
   false and the query returns 0. Inject a realistic *cluster*, not one
   feature.

2. **Auto-download clobber (the real blocker).** On camera move the app's
   `UniversalCountryCacheManager` auto-downloads spots/airspaces for the
   current country and rewrites the cache — clobbering injected TEST data
   *after* `@Before` but right around when the overlay first queries it.
   The injected data is present immediately after injection
   (`isCached=true`) but gone by query time. This is a flaky race that
   makes on-map overlay-render assertions unreliable.

   The fix is harness-level: suppress `UniversalCountryCacheManager`
   auto-download during instrumented tests (e.g. a test flag), so injected
   data is the single source. Until then, overlay-render tests should
   verify the *renderer* directly (see `PgSpotRenderTest` /
   `HazardRenderTest` montages) and SKIP the on-map assertion when the
   overlay received 0 features (see `PgSpotLegibilityTest`), rather than
   assert against a clobbered cache. `ResourceAuditTest` dodges this
   because it injects *weather* via Redux (not the spatial cache), so
   nothing downloads over it.

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
