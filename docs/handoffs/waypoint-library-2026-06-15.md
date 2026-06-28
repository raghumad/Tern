# Handoff — Waypoint Library & In-Flight Task UI

**Date:** 2026-06-15
**Branch:** `mezulla` (34 commits ahead of `origin/mezulla` at time of writing)
**Status:** All work committed, working tree clean. Claim suite: **84 HELD / 0 BROKEN / 7 GAP**.

> Picking this up on a fresh machine? `git checkout mezulla`, read this file, then skim
> the commits listed under "What shipped". The thread context does not travel through git —
> this doc is the bridge.

---

## Workflow constraint (still in effect)
- Commit freely on `mezulla`. **Never `git push`** — the human does that.
- End commit messages with: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

---

## The model we built toward
Comp scenario (PWC / airtribune): organisers **issue waypoints first** (`.cup`/`.wpt`/`.gpx`
downloads), then publish **tasks** on the day. So:

- **Waypoints exist independently of tasks.** A standalone *waypoint library*.
- **A task is an ordered list of waypoints** + per-task properties (gates, cylinders).
- Tasks **reference** library waypoints by ID (not copies). Editing/re-importing a library
  waypoint flows into every task that uses it.
- **Waypoints are first-class on the map** — they render like PG spots and tap → site-aware
  weather / Flyability.

This is fully realised and proven against real LCC26 coaching files.

---

## What shipped (newest → oldest, on `mezulla`)
```
fa179d9 feat(waypoints): Map Layers toggle + tap-a-row to locate on the map
91750ae feat(waypoints): render library on the map + tap for weather (PG-spot parity)
2992caf feat(task): Stage B3 — comp-day round-trip (bind imported task to library)
f089bcc fix(task): close cylinder polygon rings exactly (crash on awkward coords)
c9a0632 feat(waypoints): support FormatGEO .wpt (airtribune/GpsDump "FS" export)
a605ffa feat(task): Stage B2 — reference resolver (library edits flow into tasks)
7b7d5b5 feat(task): Stage B1 — build a task by picking from the waypoint library
beda123 feat(waypoints): Stage A — standalone waypoint library (exists without tasks)
6762240 feat(task/inflight): Phase 2 — task ribbon with Go-to / Skip / Back overrides
7494a62 feat(task/inflight): Phase 0+1 — tap-to-select waypoints + compass rosette nav
```
(plus several `docs(backlog)` design-decision commits between them).

### In-flight task UI (Phases 0–2)
- Map tap → waypoint selection.
- **Compass rosette**: cyan WP badge (with pointed tail at `waypointBearingDeg`), amber wind
  arrow (rim-to-rim), red N carat. The ordinal number is a **non-rotating** `Text` overlay
  (earlier bug: it rotated upside-down / was obscured by the wind arrow).
- Next-WP readout under the compass.
- Tag feedback: 140 ms haptic + cyan flash when a waypoint is reached.
- Planning capsules (top distance / bottom task name) are **hidden in flight** (distracting).
- **Phase 2 ribbon**: modal sheet, tap-a-dot Go-to + manual Skip/Back. Dots show the
  **ordinal** (codes don't fit 40 dp); missing-link points get an amber "!" badge.

### Waypoint library (Stages A, B1–B3)
- **A** — standalone library: import `.cup` / `.wpt` / `.gpx`, search, delete, persist.
- **B1** — build a task by picking waypoints from the library (multi-select, pick-order badges).
- **B2** — `TaskResolver` overlays live library identity onto linked task points at read time.
- **B3** — import a task file → `bindToLibrary` matches points to the library **by code**,
  stamps `libraryWaypointId`, flags any missing.

### PG-spot parity
- Library waypoints render as violet markers (declutter via `iconAllowOverlap=false`).
- Tap → reuses the PG-spot weather path (`ShowWeatherDetails` + `FetchWeatherForPGSpot`,
  keyed by `"wp|code|lat|lon"`), site-aware via `SiteContext(elevationM = alt)`.
- **Map Layers → Waypoints** visibility toggle (default on).
- **Tap a library row → locate** (centre + zoom to 13, close the sheet).

---

## Architecture map (where things live)
Root: `tern-android/app/src/main/kotlin/com/ternparagliding/`

| Concern | File |
| --- | --- |
| Standalone waypoint model | `model/LibraryWaypoint.kt` |
| Task model + `Waypoint.libraryWaypointId` | `model/Task.kt` |
| Reference resolver / bind | `overlay/task/TaskResolver.kt` |
| Task map layer + cylinder geojson | `overlay/task/TaskLayer.kt`, `overlay/task/TaskGeoJson.kt` |
| Tag haptic/flash feedback | `overlay/task/TagFeedbackOverlay.kt` |
| Waypoint map layer + overlay | `overlay/waypoint/WaypointLibraryLayer.kt`, `…/WaypointLibraryOverlay.kt` |
| File parsing (CUP/WPT/FormatGEO/CompeGPS/Ozi/GPX) | `utils/io/WaypointFileParser.kt` |
| Library persistence (JSON) | `utils/cache/WaypointLibraryStore.kt`, `redux/WaypointLibraryPersistence.kt` |
| Import entry point | `utils/io/TaskIOManager.kt` (`importWaypointsFromUri`) |
| State (`waypointLibrary`, `OverlayState.waypoints`, `resolvedTasks()`/`resolvedSelectedTask()`) | `redux/MapState.kt` |
| Actions | `redux/MapActions.kt` |
| Reducers | `redux/MapReducers.kt`, `redux/TaskReducers.kt` |
| Compass rosette + readout | `ui/components/MapControls.kt` |
| Map mount point (layers, overlays, compass) | `ui/components/MapViewContainer.kt` |
| Library screen / picker / ribbon | `ui/screens/WaypointLibraryScreen.kt`, `ui/components/WaypointPickerSheet.kt`, `ui/components/TaskRibbonSheet.kt` |
| Settings toggle | `ui/components/SettingsSheet.kt` |
| Screen wiring (ribbon/library/picker sheets) | `ui/screens/TernMapScreen.kt`, `ui/screens/TaskListScreen.kt` |

### Conventions worth remembering
- `mapReducer(state, action)` is pure — use it directly in claim tests.
- MapState extension funcs must be called as `state.resolvedTasks()` (with import), **not**
  `redux.resolvedTasks(state)` — the latter doesn't compile (bit us several times).
- MapLibre `Polygon` rings must be **exactly closed** (`ring.add(ring[0])`) — computing the
  closing vertex from angle 2π leaves a sub-ULP gap that crashes near lon/lat 0. See `f089bcc`.
- Overlay toggles flow through one `SetSettingsOverlayEnabled(stringKey, enabled)` action;
  typos silently no-op via the `else` branch.

---

## Verification
- Claim tests: `cd tern-android && ./gradlew :app:testDebugUnitTest` (or the project's claim
  runner). Report format: "N HELD / N BROKEN / N GAP".
- Compile check: `./gradlew :app:compileDebugKotlin`
- Build + install: `./gradlew :app:installDebug`
- Real fixtures used (airtribune LCC26 coaching):
  - `…/waypoints/2026/06/lcc26-coaching.SeeYou.cup`
  - `…/waypoints/2026/06/lcc26-coaching.FS.wpt` (FormatGEO)

---

## Possible next steps (none requested yet — confirm before starting)
- Tap a **non-active** ribbon dot → "Info peek" (read-only) instead of immediate Go-to.
- Inline single-waypoint editing in the library.
- Persist the Waypoints overlay toggle across launches (currently in-memory).
- Per-task cylinder/gate editing UI (the model carries the props; the editor is thin).
