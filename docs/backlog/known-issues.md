# Known issues

App-cleanup issues that are known and parked while the focus is on
mezulla/LoRa and the flight-deck. This is **not** an active to-do list — it's
the starting point for when focus shifts back to app cleanup.

> **History.** The detailed pre-teardown list — resolved Phase-2 fixes and the
> instrumented test-failure clusters — is archived in
> [../archive/known-issues-pre-teardown-2026-06.md](../archive/known-issues-pre-teardown-2026-06.md).
> It still holds the useful product postmortems (Hilbert haversine query fix,
> `icaoClass` off-by-one, the dense-airspace main-thread freeze fix, the
> auto-download gate). The BDD/instrumented test suite those clusters were
> measured against has been removed; verification is now claim-driven — see
> [../claims.md](../claims.md).

## UX discovery findings (on-device walk, 2026-06)

From a pilot-perspective walk of the task surface on the Ulefone (UX before features).

- **✅ B1 — Back exits the whole app from the task panel. FIXED.** Added a prioritised
  `BackHandler` in `TernMapScreen` that closes the topmost open layer (panel / modes /
  full-screen overlays) in z-order; only when nothing is open does Back fall through to
  the system. On-device verified (Back closes the panel, app stays focused).
- **✅ B2 — create-waypoint forced a full-screen modal. FIXED.** A long-press drop now
  flags its selection `isNew` (`WaypointSelection.isNew`); the editor opens only on an
  explicit tap of an existing point, so dropping shows the point in the panel and you keep
  dropping. `TaskSnapClaimsTest` + on-device verified.
- **✅ B4 — dock reachability while the panel is open. NON-BUG.** The dock is anchored
  top-right and never overlaps the bottom panel; the gear opens Settings fine. The earlier
  "unresponsive" reading was a screenshot-coordinate miss, not a real defect.
- **B3 — base-map label clutter** (airspace MSL labels + long PG names overlap). **Folded
  into** the "airspace is everything-always" relevance item below — same root (no declutter
  / altitude-unaware fills). **Open** — the strongest safety candidate next.

## Open product items (carried forward)

These are real, product-level (not test-harness) issues that were not resolved
before the test teardown. Re-validate each via a claim-driven test as it's
picked up.

- **~~Airspace `type=4` mislabelled "MILITARY".~~ ✅ Already fixed.**
  `AirspaceGeoJson.resolveAirspaceClass` maps `type=4 → CTR`. (Confirmed during the
  airspace-declutter work — the known-issue was stale.) *(claim: K2 airspace · Correct)*

- **Airspace visualization is "everything, always" — needs a relevance model.**
  Surfaced 2026-06-14 reviewing the Aravis flight-deck replay: it looked like the
  whole team was flying *inside* airspace. They weren't — these are real legal
  tracks below the TMA floor (e.g. Geneva TMA floor ≈ 8,500 ft MSL; pilots at
  ~6,600 ft). The overlay draws **every airspace footprint as a flat 25% lateral
  fill regardless of the pilot's altitude**, and over dense regions (Europe,
  India) that blankets the entire map — an instrument that's always red is the
  same as no instrument. Two intertwined problems:
  1. **Altitude-unaware** — floor/ceiling are in the data (OpenAIP
     `lowerLimit`/`upperLimit`, even shown in the label) and pilot altitude is now
     in `FlightDeckState`, but the two are never combined. A block floored far
     above you should recede, not shout. *(this is the K2 "vertical airspace
     proximity" gap — "180 m below CTR floor")*
  2. **Relevance / declutter** — even altitude-correct, showing all classes at
     all times is noise. Candidate directions (to weigh when we revisit, not
     decided): filter/dim by **class** (hard boundaries — CTR/Prohibited/Restricted
     — bold; advisory/high-FL classes faint or off), by **vertical proximity**
     (bold only what you're in or approaching), by **phase** (look-ahead while
     gliding, immediate while circling), and outline-only at low zoom with fills
     reserved for what can actually bite you. Goal: airspace becomes a
     *vertical-clearance + boundary-ahead* instrument, not wallpaper.
  **✅ Slice 1 done (2026-06):** declutter — fills zoom-gated (region view outline-only,
  no blanket), borders class-emphasised (only danger-classes bold, controlled dimmed,
  advisory faint), labels hidden below ~zoom 10. `AirspaceLayer`.
  **✅ Slice 2 done (2026-06):** altitude-aware relevance — each feature is stamped with an
  emphasis (BOLD/NORMAL/FAINT) from the pilot's live altitude (`FlightDeckState.altitudeM`)
  vs its numeric floor/ceiling, re-stamped per ~500 ft altitude bucket (cheap, no re-query).
  A block floored far above you recedes; what you're in/approaching is bold; a ground-floored
  block is bold whenever you're below its ceiling; on the ground it falls back to class
  emphasis. Pure core `AirspaceRelevance` (+ `AirspaceRelevanceTest`, incl. the Geneva-TMA
  recede case); `AirspaceGeoJson.withEmphasis`. On-device verified in flight via the Aravis
  bench replay (live 6765 ft). **Remaining (smaller):** GND/SFC floors are treated as
  surface-reaching (no per-airspace terrain to do better); a *vertical-proximity* readout
  ("180 m below CTR floor") and trajectory look-ahead are still the K2 *Timely* gap —
  **deprioritized 2026-06-21** (the relevance/declutter half that fixed the "wallpaper"
  problem is shipped; the predictive readout waits behind other backlog work).
  *(claim: K2 airspace · Correct done; Timely open but parked)*

- **FAI / competition task editor — partly re-validated.** The old suite showed failures
  around the FAI editor (`FAITaskUITest`, Chamonix/Monarca competition flows),
  likely one shared root cause in the editor labels/panel. The per-point editor — role /
  start gate / cylinder radius / time gates / rename — is rebuilt and reachable from the
  panel tile (2026-06). **✅ FAI-triangle detection done (2026-06-21):** open / flat / FAI
  classification, robust to the 5-point comp shape (was hardcoded to 4 waypoints, so comp
  triangles read as open distance); `TaskTriangleClaimsTest`. *Remaining:* surface FAI
  **points** in the panel, and full comp-flow validation. *(claim: K6 route/task)*

- **Overlay tap / select on dense clusters.** *PG-spot **and** waypoint-library
  tap are now wired* (2026-06): tapping a marker opens its weather/Flyability
  sheet via the maplibre-compose `SymbolLayer` `onClick`; ad-hoc drops also
  **ground-distance snap** to an existing spot (~150 m) so you don't stack
  duplicates. **Still open:** reliable tap/select on *dense, overlapping* clusters
  (and other layers) where the collision-declutter and hit ambiguity bite — a real
  hit-test against the MapLibre projection
  (`cameraState.projection.positionFromScreenLocation`, as `OffScreenPeerIndicators`
  uses) is the durable fix. *(claim: K3 · Frictionless)*

- **~~Weather — deferred.~~ ✅ Done (2026-06).** Shipped as the full **K4
  weather / Flyability deck**, claim-tested — see [../claims.md](../claims.md)
  (K4) and [../design/flight-deck-ui.md](../design/flight-deck-ui.md). The
  **Skew-T stability plot** is now **shipped** (a real sounding plot in the
  weather sheet, not a placeholder), and **trajectory weather** reads the
  per-waypoint forecast at each ETA along the task. The old convective-only "storm
  risk" alarm is replaced by a whole-task **Flight Risk** synthesis (wind/gusts/shear/
  convection/visibility/precip + airspace + daylight + terrain, advisory + transparent;
  `weather/FlightRisk.kt`, `TaskFlightRiskClaimsTest`); a latent ETA timezone bug
  (true-epoch ETA vs local-as-UTC forecast clock → wrong-hour sampling) was fixed in
  the synthesis. **✅ Root timezone fix done (2026-06-21):** all forecast timestamps are now
  **true epoch** — `parseForecast` reads Open-Meteo's `utc_offset_seconds` and subtracts it, the
  offset rides on `WeatherForecast.utcOffsetSeconds`/`SoarableDay.utcOffsetSeconds`, and the
  sheets format site-local via `siteTimeZone(...)` instead of a UTC `SimpleDateFormat`. Removed
  `FlightRisk`'s `toForecastClock` shim (ETAs now compare directly); silently corrected
  `WeatherMiddleware.interpolateWeatherForEta` (true-epoch ETA vs forecast hours) and `isStale()`.
  `Soarable.kt` buckets day/hour in site-local. Claim-tested in `WeatherTimeBasisClaimsTest`.
  **✅ Thermal outlook done (2026-06-21):** a numeric **w\*** (convective velocity scale)
  climb-rate forecast per daylight hour — `shortwave_radiation` + `boundary_layer_height` added
  to the fetch, the w* model + working window/peak in `weather/ThermalForecast.kt`, surfaced as
  `ThermalOutlookCard` under the Soarable card (with parcel thermal top + cumulus base). Live
  Open-Meteo confirmed the new params return data (won't 400 the fetch); degrades to qualitative
  strength when inputs are absent. `ThermalForecastClaimsTest`. Remaining *gap:* **corridor-DEM
  terrain clearance** along legs (async elevation). *(claim: K4)*

## Not bugs (documented so they aren't re-investigated)

- **Mt Herman PG spot invisible at region zoom.** Expected: MapLibre
  `iconAllowOverlap=false` collision-declutter hides it when a denser nearby
  marker wins at low zoom. `inCache=2`, `inRenderedInventory=true`. No safety
  impact.
