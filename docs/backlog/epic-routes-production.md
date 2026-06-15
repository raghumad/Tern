# Epic: Routes to production-ready

> **Status: IN PROGRESS (2026-06).** Routes work end-to-end on paper but are
> not production-ready: the route *line* renders, but waypoints/cylinders
> don't, there's no map-based editing, and most route tests are dishonest
> (assert Redux/tags, not the rendered map). This epic makes routes genuinely
> good and backs every feature with a claim-driven test (see [../claims.md](../claims.md)).

## How we work this epic

One **theme** at a time, via the standing loop:
implement → a claim-driven test (replay a real task; assert observable behavior —
store state, cache, the outcome the pilot depends on) → user reviews the report → push as a new baseline →
next theme. Definition of done: the claim is demonstrated AND the user has
reviewed it. A test only counts if it asserts the **outcome the pilot depends
on**, never just a Redux flag or tag existence ("assert downstream, not
upstream").

## Current state (honest, 2026-06)

**Works + honestly tested:** route line (neon cyan + dark casing), persistence
on edit (`RoutePersistence` observer → `RouteCache`), proximity resurfacing
(`RouteProximityOverlay` → `SurfaceNearbyRoutes`), waypoint drag → cache,
XCTSK verbose import/export (file-level, `RouteIOManagerTest`).

**Broken or missing:**
- Waypoint markers + labels don't render — `RouteLayer` uses `marker-15`
  sprite + `textField` glyphs, which the raster styles don't ship.
- FAI radius cylinders not drawn.
- No map-based editing — drag state machine exists in Redux but no gesture
  wires it; no tap-to-select on the map.
- No selection highlight for the selected route/waypoint.
- No startup restore (routes only resurface by proximity).
- FAI points computed but never displayed; triangle detection bug (0.4 km
  closure heuristic misclassifies real tasks).
- Tile pre-cache of the route corridor not automated.
- Airspace check is point-in-polygon only (misses leg crossings).
- Compressed-XCTSK / QR codec untested.

**Test coverage:** the old instrumented route tests (~26) were mostly dishonest
(asserted tags/Redux, not the rendered map) and have been removed with the rest
of the BDD suite. Routes will be re-covered by claim-driven tests as each theme
lands — asserting the pilot-visible outcome, not existence.

## Feature set by theme

### Theme 1 — Make routes visible  *(DONE — baseline fd8ecd3)*
- [x] Cylinder-centric waypoint markers as icon bitmaps (role colour + short
      code; name + drawn radius glyph at the detailed zoom tier).
- [x] FAI radius cylinders (role-coloured fill + ring) — the waypoint identity.
- [x] Leg-distance pills on the line at each leg midpoint ("25 km"), glanceable.
- [x] Zoom-adaptive labels (code → code+name+radius).
- [x] Selection highlight (enlarged centre + halo).
- [x] Route line + dark casing (thinner line).
- Design: cylinder-centric, no-tap/glanceable — richer than legacy varios
  because the phone allows zoom-adaptive density. (Claim-driven coverage pending.)

### Theme 2 — Map-based editing
- [ ] Tap a waypoint on the map → select → edit.
- [ ] Long-press-drag a waypoint on the map → reposition (wire the existing
      Start/Update/End WaypointDrag Redux state machine to a gesture).
- [x] Add waypoint via long-press (exists, via smart suggestion).

### Theme 3 — Route management
- [ ] Display FAI points in the route panel.
- [ ] Fix triangle / FAI-triangle detection (derive from waypoint types, not
      the 0.4 km closure heuristic).
- [ ] Rename / delete / reorder / visibility — verify + honest tests.
- [ ] Share (QR / Android intent) — honest test.

### Theme 4 — Persistence completeness
- [ ] Startup restore of cached routes (load all on launch, not only nearby).
- [ ] Auto tile pre-cache of the route corridor on add/select (offline-first).
- [x] Write-on-edit + proximity surfacing + route-id round-trip fix (done).

### Theme 5 — Import / export robustness
- [ ] Validate compressed-XCTSK / QR roundtrip with known vectors.
- [ ] GPX import/export (optional).

### Theme 6 — Planning intelligence
- [ ] Airspace segment-intersection (each leg vs airspace, not just points).
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
- [ ] Auto-advance on cylinder entry: logic only (unit) — not flight-verified,
      since the one bundled task (Italy) isn't co-located with a replay.
- [ ] Claim-driven test: replay a task, assert the active waypoint advances on
      cylinder entry and the off-screen chip points at it.
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
