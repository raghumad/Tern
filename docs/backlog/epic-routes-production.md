# Epic: Routes to production-ready

> **Status: IN PROGRESS (2026-06).** Big jump since the original entry: waypoint
> markers + FAI cylinders now render (rich markers — disc + name + cylinder +
> elevation + gate), map-based editing (tap-select, long-press create with
> ground-distance snap, move-mode reposition) is wired, and the "route"
> vocabulary is renamed to **task** throughout the code (`overlay.route` →
> `overlay.task`, `model/Task.kt`; see
> [epic-task-vocabulary-and-sharing.md](epic-task-vocabulary-and-sharing.md)).
> Remaining: FAI-triangle correctness, startup restore, corridor tile pre-cache,
> per-leg airspace, and import/export robustness. Every feature is backed by a
> claim-driven test (see [../claims.md](../claims.md)).

## How we work this epic

One **theme** at a time, via the standing loop:
implement → a claim-driven test (replay a real task; assert observable behavior —
store state, cache, the outcome the pilot depends on) → user reviews the report → push as a new baseline →
next theme. Definition of done: the claim is demonstrated AND the user has
reviewed it. A test only counts if it asserts the **outcome the pilot depends
on**, never just a Redux flag or tag existence ("assert downstream, not
upstream").

## Current state (honest, 2026-06)

**Works + honestly tested:** task line (neon cyan + dark casing); **waypoint
markers** (role-coloured disc + code, with name + cylinder radius + elevation +
time-gate pills at the detailed zoom tier); **FAI cylinders** (role-coloured fill
+ ring); leg-distance pills; selection highlight; **map-based editing** —
tap-select, long-press create (with ground-distance snap to an existing spot),
and move-mode reposition; persistence on edit (`TaskPersistence` → `TaskCache`),
proximity resurfacing (`TaskProximityOverlay` → `SurfaceNearbyTasks`), XCTSK
verbose import/export (file-level, `TaskIOManagerTest`).

**Broken or missing:**
- ~~triangle detection bug~~ **✅ fixed + claim-tested (2026-06-21):** classifies open /
  flat / FAI triangle, robust to the 5-point comp shape (start snapped on a corner, goal
  returns to start) — was hardcoded to 4 waypoints (`TaskTriangleClaimsTest`). FAI **points**
  computed (`faiPoints`) but still not surfaced in the panel UI.
- Airspace check is point-in-polygon only (misses leg crossings).
- Compressed-XCTSK / QR codec untested.

**Test coverage:** the old instrumented route tests (~26) were mostly dishonest
(asserted tags/Redux, not the rendered map) and have been removed with the rest
of the BDD suite. Routes will be re-covered by claim-driven tests as each theme
lands — asserting the pilot-visible outcome, not existence.

## Feature set by theme

### Theme 1 — Make routes visible  *(DONE — baseline fd8ecd3)*
- [x] Cylinder-centric waypoint markers as icon bitmaps (role colour + short
      code; at the detailed zoom tier: name pill + cylinder radius + elevation
      + time-gate, balanced left/right of the disc).
- [x] FAI radius cylinders (role-coloured fill + ring) — the waypoint identity.
- [x] Leg-distance pills on the line at each leg midpoint ("25 km"), glanceable.
- [x] Zoom-adaptive labels (code → code+name+radius).
- [x] Selection highlight (enlarged centre + halo).
- [x] Route line + dark casing (thinner line).
- Design: cylinder-centric, no-tap/glanceable — richer than legacy varios
  because the phone allows zoom-adaptive density. (Claim-driven coverage pending.)

### Theme 2 — Map-based editing
- [x] Tap a waypoint on the map → select → edit. **Wired** (`TaskLayer.onClick` →
      `SelectWaypoint` → `EditWaypointScreen`); L1-verified (`TaskMapClaimsTest`).
- [x] Reposition a waypoint on the map → **move-mode** (2026-06): tap-select → editor
      → **"Move on Map"** → the editor steps aside + a banner shows → **tap the new spot**
      (Cancel restores). Chosen over press-and-hold drag, which felt wrong on-device.
      Reuses the existing Start/Update/End/CancelWaypointDrag Redux machine (the drag
      gesture is unwired by design — a single tap commits via `onMapClick`). Logic
      L0-covered (`TaskMutationClaimsTest`); the commit's L1 is `@Ignore`d because a
      single tap can't be injected into the GL SurfaceView on-device (long-press swipe
      can) — correctness backed by click-dispatch-order analysis + the L2 checklist.
- [x] Add waypoint via long-press. **Correction (2026-06):** this was marked done but
      the *map* long-press gesture was never wired after the MapLibre-Compose
      migration — only the smart-suggestion dialog path existed. Now wired
      (`MapViewContainer.onMapLongClick`) and L1-verified on-device.

### Theme 3 — Route management
- [ ] Display FAI points in the route panel.
- [ ] Fix triangle / FAI-triangle detection (derive from waypoint types, not
      the 0.4 km closure heuristic).
- [ ] Rename / delete / reorder / visibility — verify + honest tests.
- [ ] Share (QR / Android intent) — honest test.

### Theme 4 — Persistence completeness  *(DONE — 2026-06)*
- [x] Startup restore of cached tasks (load all on launch via `TaskPersistence`
      hydrate → `SurfaceNearbyTasks`, not only nearby).
- [x] Auto tile pre-cache of the task corridor on add/select (offline-first):
      `TaskPlanningMiddleware` kicks a debounced, dedup'd `TaskTileCacher` download
      (restart-safe via `isTaskCached`). NOTE: a `CacheTilesButton` composable exists
      but is **not wired into any screen** — caching is auto-only today; wire the manual
      control into the task panel if an explicit trigger is wanted.
- [x] Write-on-edit + proximity surfacing + task-id round-trip fix (done).

### Theme 5 — Import / export robustness
- [ ] Validate compressed-XCTSK / QR roundtrip with known vectors.
- [ ] GPX import/export (optional).

### Theme 6 — Planning intelligence
- [x] Airspace segment-intersection (each leg vs airspace, not just points) —
      `SpatialSafetyUtils.taskAirspaceConflicts` flags a leg that *crosses* controlled
      (A/B/C) airspace even with both waypoints outside, handles Polygon + MultiPolygon
      + holes, and names the airspaces crossed (HUD: "AIRSPACE · crosses Geneva TMA").
      `SpatialSafetyUtilsTest`.
- [ ] Verify storm-risk-along-route detection. *(Weather HUD stays deferred to
      the separate weather redesign.)*

### Theme 8 — Active-waypoint navigation (buddy-style guidance)  *(IN PROGRESS)*
The pilot's ask: "if a task is defined, the next waypoint should show up just
like a buddy shows up on screen." Mirrors the Mezulla peer treatment.
- [x] `Waypoint.description` (human name for a cryptic code: "B4" → "Gold's
      Point") + `displayName`; editable in `EditWaypointScreen`.
- [x] Active-task progress in Redux (`activeWaypointId` + `taggedWaypointIds`);
      `TaskProgressOverlay` auto-advances the active waypoint on cylinder entry
      (selected route = the active task).
- [x] On-map highlight of the active waypoint (enlarged + halo, via `RouteLayer`).
- [x] `OffScreenWaypointIndicator` — edge chip when the next waypoint is off
      viewport: direction arrow + name/description + distance + required glide
      ratio (when a live altitude is available). Sibling of
      `OffScreenPeerIndicators`.
- [x] XCTSK import now reads `waypoint.description` (was dropped) — and export
      writes it back. Without this the description field had no data path.
- [x] On-device verified (imported a real comp task, srs-2026-2-task1.xctsk):
      the off-screen chip renders pinned to the edge with the direction arrow +
      the human description ("RUBBIO ALT TO", not the code "D18") + distance, and
      only appears once a GPS fix exists (the engine needs own-position).
- [x] Auto-advance on cylinder entry — **claim-tested** by replaying the real Bir
      Billing track (richard's IGC) through the exact `TaskNavigator` logic the UI uses,
      with cylinders anchored on the flown track: the active target advances through every
      waypoint in order, then clears, with a finite bearing while ahead (`TaskNavClaimsTest`).
- [ ] Polish:
      - chip can overlap the right-edge dock when the target is nearly due-east
        (distance text slid under the Share button); tighten the dock inset /
        clamp the chip width.
      - distinct "target" styling vs editing-selection (currently share a halo).
      - arrival-altitude judged colour (make/no-make).
      - decouple active task from selection so guidance survives dismissing the
        detail panel.

### Theme 7 — Claim coverage  *(woven through 1–6)*
Each route claim (visible waypoints/cylinders, offline corridor, FAI
correctness, edit-on-map) backed by a claim-driven test that asserts the
pilot-visible outcome — replay a real task, drive the data+logic stack — never
tag/Redux existence. See [../claims.md](../claims.md).

## Definition of done for the epic
All themes implemented; every route feature backed by a passing claim-driven
test; routes are usable offline end-to-end (plan at home →
restart → fly the same task in the field, see every waypoint/cylinder, edit on
the map). Then routes is a production baseline.
