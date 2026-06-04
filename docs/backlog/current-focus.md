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

### Phase 0 — Structural cleanup (full pass)

Clear the bench so stabilization happens on a tidy, honest codebase.

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

### Phase 1 — Trustworthy harness

- Test flag to suppress `UniversalCountryCacheManager` auto-download during
  instrumented tests, so injected TEST data is the single source (kills the
  overlay-render race documented in known-issues.md).
- Rebuild the 6 gesture helpers on MapLibre projection
  (`cameraState.projection.screenLocationFromPosition` /
  `positionFromScreenLocation` — now available) so gesture tests are real,
  not tautological Redux dispatches.

### Phase 2 — Fix the real broken workflows

Now that honest assertions are possible, fix the product-failure clusters
from [known-issues.md](known-issues.md), converting each dishonest test to
honest *as* it's fixed: route-planning import-recenter → waypoint-drag
cache loss → FAI editor → Settings units → overlay tap/select.

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
- **Consistent map-marker iconography.** Markers are converging on a single
  language — peers use Nerd Font glyphs (`MezullaIcons`), hazards use
  amber/red halos, PG spots use the Tern bird badge. Idea: give
  **route waypoints** a glyph too — `nf-md-routes` — for a consistent look
  (same icon-bitmap pattern as `PgSpotLayer`/`PeerLayer`, since glyph
  `textField` doesn't render on Tern's raster styles).
