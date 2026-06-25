# Current focus

> The previous focus — **the Aravis replay milestone** — is **ACHIEVED**
> (2026-06). Its full text is preserved in
> [archive/current-focus-aravis-replay-achieved.md](../archive/current-focus-aravis-replay-achieved.md).
> This file now points at the next focus.

## Milestone achieved: peers on the map, on real hardware

The end-to-end peer-awareness path works and is verified on real hardware
(LilyGo T-LoRa board + Power Armor 14 Pro), across three real flights:

| Scenario | Pilots | Gradle task | Status |
|----------|--------|-------------|--------|
| Aravis (France) | 4 | `aravisCycleTest` | ✅ passing |
| Edith's Gap (USA) | 2 | `edithsGapCycleTest` | ✅ passing |
| Bir Billing (Himalayas) | 3 | `birBillingCycleTest` | ✅ passing |

What that proves end-to-end: GPS → phone → BLE → board → (simulated LoRa
receive) → BLE → phone → map. Delivered along the way:

- **Persistent, self-healing BLE link** with auto-reconnect (PR #20) and a
  reliability suite (T2 reconnect-after-reboot, T4 MTU 517, T6 heartbeat,
  T7 link badge, F5 PHY 2M).
- **Meshtastic ToRadio/FromRadio codec**, position send/receive, mock-GPS
  from IGC, `VirtualPeerInjector`, `MEZULLA_TEST_BUILD` radio-kill.
- **Full peer HUD** (replaced the green dot): callsign, staleness-coloured
  puck, heading/track arrow, relative altitude vs me, distance, view-mode
  metric (SAFETY/CLIMB/TACTICAL), STALE/LOST status, zoom declutter.
- **Off-screen buddy indicators** — screen-edge chips (name + distance)
  for buddies outside the map view; essential on wide XC.
- **Unified test harness** — one `MezullaPeerCycleTest` base drives the
  whole cycle; the three scenarios are thin subclasses differing only by
  IGC files.

## Next focus: STABILIZE before new features (2026-06)

> **Reprioritized 2026-06-03.** The peer-mesh milestone is in. Before
> building the next safety feature (SOS UI), the decision is to **stabilize
> core functionality and workflows first** — because right now the test
> suite can't be trusted to tell us what's broken, and several user-facing
> workflows are quietly failing. SOS UI (Epic 01 Stories 1.4–1.7) is
> **deprioritized** until the base is solid.

**Why stabilize first:** the suite is partly *dishonest*. Of ~26 route
tests, only 3 pass honestly — ~14 assert a Redux flag/tag instead of a
pilot-visible outcome, and the overlay-render tests skip their assertion
when the cache is empty (a flaky auto-download race). Green ≠ verified. You
can't stabilize what you can't measure, so the order is **make green
trustworthy → let the honest reds expose the real broken workflows → fix
those.**

Sequenced phases (do in order):

### Phase 0 — Structural cleanup (full pass) — ✅ DONE (2026-06)

Clear the bench so stabilization happens on a tidy, honest codebase.
**Completed 2026-06** (9 commits, clean build + unit suite 375/0 throughout):
0a deleted the dead OSMDroid rendering code (GeoJsonUtils 927→301, the last
OSMDroid View dependency); 0b moved 64 `.kt` files from the `java/` to the
`kotlin/` source root; 0c split the four god-files (MapReducers→Map/Route/
Weather reducers; RouteDetailPanel→panel + RouteDetailsContent +
RoutePlannerTabs; UniversalCountryCacheManager→ +CountryAccessTracker;
MapOverlayCacheUtils→ +OverlayGeoJsonParser); 0d reorganized `utils/` into
`cache`/`geo`/`diagnostics`/`io` subpackages and deduped the GeoJSON
nested-property accessors. **Deferred with rationale:** a shared cache base
class — the three caches diverge materially (RouteCache is routeId-keyed),
so a forced base would be leaky; revisit after Phase 1 makes the harness
trustworthy. Original plan below for reference.

- **0a. Delete verified dead code.** `spike/MapLibreSpike.kt` (unreferenced
  POC); the 6 OSMDroid gesture methods in `MapTestHelper.kt` (0 live refs —
  keep the live `grantLocationPermissions`/`injectMockLocation`/
  `waitForMapTiles` helpers); the 4 unused OSMDroid rendering methods +
  private helpers in `GeoJsonUtils.kt`.
- **0b. Finish the OSMDroid→MapLibre migration.** *New finding (2026-06):*
  the migration is half-done. OSMDroid **rendering** (`MapView`/`Polygon`/
  `Overlay`) now survives in exactly one file — `GeoJsonUtils.kt` — and all
  of it is dead (0 callers). The other ~34 files only use osmdroid
  `GeoPoint`/`BoundingBox` as lightweight geometry **data types**; those are
  keepers for now (a later pass could swap them for a neutral geometry type,
  but it's not dead code). Deleting the dead rendering methods removes the
  last real OSMDroid-view dependency from production.
- **0c. Source-root move.** 63 `.kt` files live under `app/src/main/java/`
  instead of `app/src/main/kotlin/`. Mechanical relocation; biggest DX wart.
- **0d. Split god-files.** `GeoJsonUtils.kt` (927, shrinks a lot after 0a),
  `MapReducers.kt` (774, split by action category),
  `UniversalCountryCacheManager.kt` (720), `RouteDetailPanel.kt` (603).
- **0e. Dedup + reorg.** Three near-identical cache classes
  (Airspace/PGSpot/Route) → a shared base; three near-identical GeoJSON
  converters → a `GeoJsonPropertyResolver`; break the `utils/` junk-drawer
  (22 files, 6.9k lines) into domain packages.

### Phase 1 — Trustworthy harness — ✅ DONE (2026-06)

- ~~Test flag to suppress auto-download~~ **Already gated** — the overlay-
  render race was found to be already fixed: `CountryPreloadMiddleware` bails
  on `isTestMode()` before any download, and the only other download path
  (`checkForUpdates`) has no production caller. Marked RESOLVED in
  known-issues and locked with `Phase1HarnessTest.autoDownloadSuppressedInTestMode`.
- ~~Rebuild gesture helpers on MapLibre projection~~ **Done — then partly lost.**
  `MapViewContainer` still exposes the live projection via `MapProjectionTestHook`,
  **but** the `MapTestHelper` gesture helpers, `Phase1HarnessTest`, and `AppLaunchTest`
  were later deleted (and the gradle-referenced cycle tests never existed in-tree) —
  so by 2026-06 there was *no* running pilot-perspective verification at all.
  **Correction (2026-06 remediation):** rebuilt from scratch as
  `androidTest/.../map/MapDriver.kt` — UiAutomator raw-coordinate touches + the
  activity-scoped `MapStore` as oracle (`ComposeTestRule` can't drive the never-idle
  GL map). First reliable L1 (pilot-outcome) claims green on-device:
  `TaskMapClaimsTest` (create / tap-select / delete-no-crash). See
  [../claims-pilot-validation.md](../claims-pilot-validation.md).

### Phase 2 — Fix the real broken workflows — IN PROGRESS

Now that honest assertions are possible, fix the product-failure clusters
from [known-issues.md](known-issues.md), converting each dishonest test to
honest *as* it's fixed.

- **✅ Route clusters #1 + #2 done (2026-06).** 12/12 route-cluster tests
  green (was 6/12). Two **real product bugs** fixed along the way:
  (a) auto-framing a route moved the MapLibre camera but never synced
  `MapState.center` back to Redux (programmatic moves are skipped by the
  GESTURE→Redux feedback), leaving the app-level centre stale —
  `MapViewContainer` now syncs the settled camera after `animateTo(bbox)`;
  (b) `EditWaypointScreen` never showed the waypoint label, so you couldn't
  tell which point you were editing. The remaining failures were dishonest/
  stale tests (wrong expected coords, `assertExists` on >1 node, RFC-005
  panel-collapse hiding controls, list shows "N WPs" not labels, and a
  synthetic Compose swipe that can't reach the MapLibre surface — now a real
  UiAutomator swipe).
- **✅ Settings units cluster #4 done (2026-06).** `testUnitPreferences` was
  passing dishonestly (clicked a unit, asserted the button still existed) and
  flaked in the blanket run (Units scrolled offscreen in the settings
  `LazyColumn`). Now scrolls into view and verifies the selection genuinely
  changed — the highlight (`assertIsSelected`) *and* the `settingsState`
  preference. Small **product** win: the unit picker's colour-only selection
  is now exposed to the semantics tree (screen-reader + test visible).
- **✅ Weather (#6) shipped as a full deck (2026-06) — not just a test fix.**
  What was scoped as "fix the weather cluster, last" became the **K4 weather /
  Flyability feature**, all claim-tested (board ~42 HELD · 0 BROKEN · 3 GAP):
  site-aware Flyability (wind-vs-orientation on the surface wind + cloudbase-vs-launch),
  the **soarable-window scan** ("when today is it flyable", daylight-bound, daily digest),
  live **source policy** (per-country model + MET Norway fallback), **surface + aloft
  wind**, **pilot-preferred units + persistence**, and a glass-cockpit weather UI
  (bottom sheet · soarable card · **orientation dial w/ wind barbs** · 48 h chart with
  soarable shading + gust envelope + value axes). Spedmo's soarable API is parked as
  Story 3.10 (our offline scan is tuned to agree with it).
- **Update (2026-06-21):** K7 is much further along than this note implies. **Done + on-device
  verified:** `$XCTRC` BLE ingest, NMEA reassembly, parser, the circling-wind brain, climb-tinted
  flight track, vario/altitude/wind HUD, phase-aware camera-follow. **Closed this session (JVM,
  no hardware):** the **HUD stage selector** (`FlightMetrics.hudContext` — L/D gliding / ▲gain
  climbing / cloudbase-gap when near; `FlightStateClaimsTest`) and the **source ladder**
  (positioned vario → XC_TRACER, link-loss → PHONE, no flap; `FlightDeckSourceClaimsTest`).
  **Remaining K7:** the **vario picker + device-memory** (scan→pick→persist MAC, so multiple
  varios on launch don't grab the wrong one — needs hardware to validate), and in-flight checks
  (live wind while circling, GPS hand-off under movement). The cloudbase-gap HUD cue is built but
  dormant until weather cloudbase is threaded into the deck.
- **In progress: the flight-deck brains (K7), then the map-hero UI.** Decision (2026-06):
  the flight deck is **map-hero** (map stays the hero; instruments frame it, not a Dynon-style
  panel split), driven by an **XC Tracer vario over BLE** as the near-term sensor source
  (before Mezulla v2). Brains-first, mirroring the weather arc. **First brain shipped:**
  `WindEstimator` — wind from drift while circling (velocity-space circle fit), claim-tested
  against a synthetic circle and a real Bir Billing thermal replay (K7 · 3 HELD). **Next
  brains:** XC Tracer `$XCTRC` BLE ingest (a 2nd peripheral beside the LoRa board) →
  `FlightState`/`Measured` fusion (vario = baro+GPS Kalman) → then fold wind into the compass
  rosette and add the vario/altitude/glide instrument bands. See
  [../design/flight-state.md](../design/flight-state.md) and
  [../design/flight-deck-ui.md](../design/flight-deck-ui.md).
- **✅ Task-planning UX overhaul (2026-06).** The task surface is now pilot-grade: one
  folded task panel (HUD folded in, dock at fixed top-right, no overlap), **add-from-map**
  centre-pin placement with **ground-distance snap** (drop near an existing spot → reference
  it, don't duplicate), real **nearby SEARCH** (PG spots are first-class waypoints),
  **per-point editing** (role / start gate / cylinder / rename from the panel tile), and
  **rich map markers** (role disc + name + cylinder + elevation + time-gate, balanced
  left/right). A single unified **waypoint flag glyph** now means "waypoint" everywhere —
  map, dock, page headers, Settings. Built on **Stage C** (the unified Spot library +
  resolver + persisted references).
- **✅ Trajectory weather = 4D flyability (2026-06).** Per-waypoint forecast read at each ETA
  along the task (engine-driven `assessFlyability` / `assessQuality` / sounding), a real
  **Skew-T** pressure-level plot in the weather sheet, and DEM **elevation backfill** for
  ad-hoc map drops.
- **✅ Flight Risk synthesis (2026-06).** The convective-only "storm risk" alarm (which
  false-fired red on a 0 km task and read "now" not the ETA) is replaced by a whole-task
  **Flight Risk** read — worst KNOWN factor across wind/gusts/shear/convection/visibility/
  precip **+ airspace + daylight + terrain**, at each waypoint's ETA, advisory + transparent
  (the pilot decides). Fixed a latent ETA timezone bug (true-epoch ETA vs local-as-UTC
  forecast clock → wrong-hour sampling) and removed the dead storm path. `weather/FlightRisk.kt`,
  `TaskFlightRiskClaimsTest`. Also: dock buttons are now centrally a11y-labelled.
- **✅ Root timezone fix (2026-06-21).** Forecast timestamps are now **true epoch** end-to-end:
  `parseForecast` reads Open-Meteo's `utc_offset_seconds` and subtracts it; the offset rides on
  `WeatherForecast.utcOffsetSeconds`/`SoarableDay.utcOffsetSeconds` for display, and the sheets
  format site-local via `siteTimeZone(...)` (was a UTC `SimpleDateFormat` compensating for the
  old local-as-UTC fiction). `FlightRisk` dropped its `toForecastClock` shim — ETAs compare
  directly; this also silently corrected `interpolateWeatherForEta` and `isStale()`. `Soarable.kt`
  buckets day/hour in site-local. `WeatherTimeBasisClaimsTest` (532/0).
- **✅ UX discovery fixes (2026-06).** From the on-device task-surface walk: **B1** Back now
  closes the open layer instead of exiting the app (prioritised `BackHandler`); **B2** a
  long-press drop no longer forces the full editor (`WaypointSelection.isNew` — editor opens
  on tapping an existing point); **B4** dock reachability was a non-bug (top-right dock never
  overlaps the bottom panel). On-device verified. See [known-issues.md](known-issues.md).
- **✅ Airspace as a real instrument (2026-06).** Both slices shipped. **Slice 1:** declutter
  — zoom-gated fills (region = outline-only, no blanket), class-emphasised borders (only
  danger-classes bold, controlled dimmed), labels hidden at region zoom. **Slice 2:**
  altitude-aware relevance — features stamped BOLD/NORMAL/FAINT from live altitude vs
  floor/ceiling (recede what's floored far above; bold what you're in/approaching), per-500ft
  bucket re-stamp. Pure `AirspaceRelevance` + tests; on-device verified (declutter + in-flight
  via Aravis replay). `type=4`→CTR was already fixed. Absorbed **B3**. *Remaining:* K2 *Timely*
  (vertical-proximity readout + trajectory look-ahead) — **deprioritized 2026-06-21**; the
  relevance/declutter half (the actual wallpaper problem) is shipped, so the predictive
  readout can wait behind other work.
- **✅ Thermal outlook (2026-06-21).** The K4 "climb-rate forecast" gap — a numeric **w\***
  (Deardorff convective velocity scale) per daylight hour from solar shortwave + boundary-layer
  depth, with the working window, peak, parcel thermal top, and cumulus base. Added
  `shortwave_radiation`/`boundary_layer_height` to the fetch (live-confirmed they return data);
  `weather/ThermalForecast.kt` + `ThermalOutlookCard` (under the Soarable card). Withholds the
  number when inputs are absent rather than faking it. `ThermalForecastClaimsTest` (537/0).
  **On-device verified (2026-06-21):** at Mt Zion (Golden, CO) the card renders the w* sparkline
  (0.3→3.2 m/s, coloured by strength), window 09:00–20:00, peak ~15:00, "3.2 m/s @ 15:00 · strong",
  and "blue thermals" base — the Top line correctly self-omits where no parcel top is computable.
- **✅ FAI-triangle detection (2026-06-21).** Was a real bug — hardcoded to exactly 4
  waypoints, so every 5-point competition triangle (start snapped on a corner + 3 TPs + goal)
  read as open distance. Now collapses coincident points to three corners and applies the 28%
  rule; open / flat / FAI classified. `TaskTriangleClaimsTest` (543/0). (FAI **points** value
  exists but isn't surfaced in the panel yet.)
- **Other open gaps:** **reliable tap/select on dense overlapping clusters (#5)** (PG-spot +
  waypoint tap shipped), and **corridor-DEM terrain clearance**.
  *Lower priority:* **K2 airspace-ahead (Timely)** (vertical-proximity + trajectory look-ahead —
  parked 2026-06-21).
  *Shipped since:* the per-point task editor, the Skew-T plot, the Flight Risk synthesis, the
  **root tz fix**, the **thermal outlook**, and the UI quick-wins (compass tap-to-north,
  recenter, contextual sharing).

### Phase 3 — Stability hardening

- Overlay **S1 soak** (a real multi-hour run, not 64×-compressed; the probe
  harness already exists — see follow-ups below).
- **S5 one-liner:** gate `PerformanceDebugger.recordStateUpdate()` behind
  `BuildConfig.DEBUG` so release builds pay zero cost on the hot path.

### Phase 4 — Resume features (deferred until 0–3 are solid)

[Epic 01](epic-01-peer-awareness-and-sos.md) Stories **1.4 SOS UI**, **1.7
graceful degradation**, **1.5–1.6 OLED status** (firmware). Then the queue
is **Epic 02 (traffic awareness)**, **Epic 04 (onboarding)**, **Epic 03
(Spedmo)**.

## Smaller follow-ups (not blocking)

- Overlay reactive-render **soak test** (see
  [overlay-infrastructure-audit.md](overlay-infrastructure-audit.md) S1) —
  guards against OOM on multi-hour flights. **Substantially delivered
  (2026-06):** opt-in `probeOverlays` mode on `MezullaPeerCycleTest`
  samples heap + airspace/PG-spot counts each tick; ran on all three
  real-board buddy flights at 64× with overlays live. Leak regression
  R²≈0 (no drift), peak heap 35–67 MB vs the 360 MB budget, while overlay
  load climbed (Aravis 180→258 airspace / 272→425 PG). What's left to
  promote it from probe to gate: a pass/fail threshold and a multi-hour
  (not 64×-compressed) run.
- App-side **release packet** on "Forget Board" (firmware handler exists;
  the app doesn't transmit it yet).
- Peer-state reset at scenario start (stale identity-only peers linger in
  test runs — cosmetic).
- **✅ Consistent map-marker iconography (2026-06).** Done: a single **waypoint
  flag glyph** (`nf-fa-flag`) now means "waypoint" everywhere — the map library
  markers, the dock task button, the Tasks / Waypoints page headers, and the
  Settings "Waypoints" row (`WaypointGlyph`) — alongside peers (Nerd Font
  `MezullaIcons`), hazard halos, and the PG-spot badge. Task *waypoints* keep the
  role-coloured disc + code (turnpoint number / T·S·E / checkered-flag goal), now
  with the name + cylinder + elevation + gate pills around them.
