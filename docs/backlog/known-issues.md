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

### Product test-failure clusters — prioritized queue (2026-06)

From a full instrumented run on real hardware (74 tests: 32 pass, 33 fail,
9 skip). ~13 failures are harness/args (cycle + BLE tests need `pairUri` via
their Gradle tasks — not product bugs). The ~20 real product failures
cluster as below; tackle in this order. (Run gives stale parser numbers —
read the `connected` XML, not `managedDevice`.)

1. **Route planning** — `AviationRoutePlanningTest` ×3. **✅ RESOLVED
   (Phase 2, 2026-06).** One was a **real product bug**: a programmatically
   auto-framed route (import/select fits the route's bbox) moved the MapLibre
   camera but never synced `MapState.center` back to Redux — the GESTURE→Redux
   feedback path deliberately skips programmatic moves — so the
   application-level centre went stale at its pre-frame value.
   `MapViewContainer` now syncs the settled camera target/zoom into Redux
   right after `animateTo(bbox)`. The other two failures were dishonest
   assertions (expected the launch waypoint instead of the bbox centroid the
   app actually frames; `assertExists` on text that legitimately matches >1
   node). Tests rewritten honestly + new `MapVisualTest.assertMapFramedRoute`.
2. **Route building & management** — `RouteManagement` ×3 (reorder/create/
   share), `MapInteraction.testLongPressNearWaypointSelectsIt` /
   `testMapSwipeAlongTrajectory`. **✅ RESOLVED (Phase 2, 2026-06).** One
   **real product gap**: `EditWaypointScreen` never rendered the waypoint's
   label, so a pilot couldn't tell which point they were editing — now shown
   under the header. The rest was test debt: RFC-005 auto-minimize collapses
   the panel on select, so the waypoint list / Share control were off-screen
   (tests now expand first); the route list summarises by "N WPs" not by
   waypoint labels (assert the count); and the swipe test injected a synthetic
   Compose touch that never reaches the MapLibre `AndroidView` surface —
   rewritten to a real **UiAutomator** system swipe (the honest way to drive a
   gesture on a GPU surface). `WaypointInteractionUX` drag was already passing
   — the "route not found in cache after drag" note was **stale**. All 12
   route-cluster tests now green on emulator; unit suite 375/0.
3. **FAI / competition tasks** — `FAITaskUITest` ×3 (scroll-to-node on
   'Start Speed Section' / 'r3000m'), `ChamonixCompetitionTest`,
   `MonarcaCompetitionTest`. Likely one shared root cause (FAI editor labels
   / panel).
4. **Settings** — `SettingsScreen.testUnitPreferences` ('Units' not found).
   Small, self-contained.
5. **Overlay tap/select** — `DenseClusterDeclutteringTest` ×2,
   `PGSpotInteractionTest`. Mostly the GPU-marker-isn't-a-Compose-node +
   auto-download race below — folds into the infra track, not standalone.
6. **Weather** *(deferred)* — `WeatherUX` ×2 (15 s timeouts),
   `RouteDetailPanelWeather` (permission infra). Parked behind a larger
   weather redesign; tackle last, once the rest is healthy.

Also hollow `@Liar` passes (gutted assertions, go green without verifying):
`DynamicMarkerTest.testPGSpotMarkerSwitchesToWindGauge`,
`SettingsScreenTest.testLayerToggles`, and 4 others — see `@Liar` grep.

### Airspaces and PG spots never render in test screenshots

Every instrumented test screenshot shows an empty map — no airspace
polygons, no PG spot markers. Root cause: `MapVisualTest.@Before`
sets `CountryUtils.setTestCountryCode("TEST")` and clears all caches.
Tests that call `givenAppIsLaunchedOnMap(countryCode = "us")` reset
the country code but the download + cache + index pipeline takes too
long, or never triggers, or times out silently.

> **Note (2026-06):** this section now covers *only* the test-harness
> injection race. A separate **production** bug was found and fixed in the
> same investigation — `SpatialDiskCache.queryNearby` used a Hilbert-index
> *window* prefilter that, because the Hilbert curve is not linear in
> spatial distance, silently dropped ~half of in-range features
> (Denver 29→60, Annecy 108→241) on real devices, not just in tests. Now
> replaced with a true haversine distance filter over `centroidLat/Lon`
> carried on the index (commit `fb9e9a4`). Airspace `icaoClass` was also
> off-by-one (Class B rendering as A, SUA 7/8 dropped) — fixed to
> 0-indexed in `AirspaceGeoJson.resolveAirspaceClass` (commit `95a1609`).
> Both were validated on the three real-board buddy flights below.

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

   **RESOLVED (2026-06, Phase 1).** The auto-download race is gated. The
   only live map-movement download path is `CountryPreloadMiddleware`, which
   bails on `CountryUtils.isTestMode()` *before* touching the cache manager
   (commit `b9f35c7` added the gate; the other `downloadAndCache` path,
   `PGSpotCache.checkForUpdates`, has no production caller). Since every
   instrumented test pins a country code (`setTestCountryCode("TEST")` in
   `MapVisualTest.@Before`), `isTestMode()` is true and no download fires —
   injected data is the single source. Locked by
   `Phase1HarnessTest.autoDownloadSuppressedInTestMode`. Probe/real runs
   that *want* downloads clear the pin (`setTestCountryCode(null)`), which is
   the intended escape hatch.

   Note the *separate* "integrity size floor" authoring rule still applies —
   inject a realistic cluster (a single feature serialises below the
   100 B/.flex floor and reads back as not-cached). And `ResourceAuditTest`
   injects *weather* via Redux (not the spatial cache), so it never touched
   this path anyway.

### screenrecord doesn't work on ATD managed device images

`VideoHelper` starts `screenrecord` but AOSP ATD emulator images
lack GPU-backed screen capture. Files are empty/missing. Options:
use a non-ATD image (heavier), use FrameCaptureHelper (screenshot
stitching), or accept video-only-on-real-device. (Real-hardware cycle
tests record fine via `screenrecord` on the physical phone.)

### Airspace/overlay polish (2026-06, minor)

Small, non-blocking items surfaced during the overlay-revival work. Not
safety issues.

- **`type=4` airspace mislabelled "MILITARY".** In OpenAIP, `type=4` is
  **CTR** (control zone), not military. Affects the label/color only —
  geometry and the (now-correct) `icaoClass` are unaffected. One-line fix
  in the type→label map.
- **Mt Herman PG spot invisible at region zoom — NOT a bug.** Confirmed
  `inCache=2`, `inRenderedInventory=true`; it disappears purely because of
  MapLibre `SymbolLayer` `iconAllowOverlap=false` collision-declutter at
  low zoom (a denser nearby marker wins). Expected behaviour, no safety
  impact. Documented here so it isn't re-investigated as a cache miss.

### MapTestHelper gesture methods dead

All 6 gesture methods use OSMDroid MapView projection. Tests that
used them were rewritten to dispatch via Redux (which is tautological
but at least doesn't crash). Real gesture testing needs MapLibre
coordinate projection — which IS now available via
`cameraState.projection.screenLocationFromPosition` /
`positionFromScreenLocation` (used by `OffScreenPeerIndicators`). These
helpers could be rewritten against it instead of OSMDroid.
