# Epic: Routes to production-ready

> **Status: IN PROGRESS (2026-06).** Routes work end-to-end on paper but are
> not production-ready: the route *line* renders, but waypoints/cylinders
> don't, there's no map-based editing, and most route tests are dishonest
> (assert Redux/tags, not the rendered map). This epic makes routes genuinely
> good and backs every feature with an honest, pixel/Compose-verified BDD test.

## How we work this epic

One **theme** at a time, via the standing loop:
implement → honest BDD test (Gherkin steps + screenshots, **managed-device**
report `pixel9proapi35DebugAndroidTest`, since connected-device runs don't
generate the BDD HTML) → user reviews the report → push as a new baseline →
next theme. Definition of done per the project rule: the BDD test passes AND
the user has reviewed the test + report. A test only counts if it asserts the
**pilot-visible outcome** (rendered pixels / Compose semantics), never just a
Redux flag or tag existence ("assert downstream, not upstream").

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

**Test-honesty crisis:** of ~26 route tests, only 3 pass honestly
(`RoutePersistenceTest`, `RouteProximityTest`, `WaypointInteractionUXTest`).
~14 are dishonest (assert tag/Redux, never run); 2–3 are `@Liar`. All the
competition tests (Chamonix/Monarca/Bir Billing), `AviationRoutePlanning` ×4,
`FAITaskUITest` ×3, `RouteManagementTest` ×4 assert existence, not rendering.

## Feature set by theme

### Theme 1 — Make routes visible  *(in progress)*
- [ ] Type-specific waypoint markers as icon bitmaps (LAUNCH / SSS / TURNPOINT
      / ESS / GOAL / LANDING), name label baked in — same icon-bitmap pattern
      as `PeerLayer`/`PgSpotLayer`/`HazardLayer` (no glyph/sprite dependency).
- [ ] FAI radius cylinders drawn around turnpoints (circle polygons from
      centre + `radius` m), semi-transparent fill + ring.
- [ ] Selection highlight — selected route line brighter/thicker; selected
      waypoint enlarged/glow.
- [x] Route line + dark casing (done).

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

### Theme 7 — Test honesty  *(woven through 1–6)*
Every route test rewritten to assert the pilot-visible outcome (pixel
signature on the rendered map, or Compose semantics for real UI), or honestly
retired/marked when it genuinely can't be tested. Targets: the ~14
dishonest/never-run route tests + the `@Liar` decluttering tests.

## Definition of done for the epic
All themes implemented; every route feature has a passing, honest BDD test
reviewed by the user; routes are usable offline end-to-end (plan at home →
restart → fly the same task in the field, see every waypoint/cylinder, edit on
the map). Then routes is a production baseline.
