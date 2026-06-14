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

## Open product items (carried forward)

These are real, product-level (not test-harness) issues that were not resolved
before the test teardown. Re-validate each via a claim-driven test as it's
picked up.

- **Airspace `type=4` mislabelled "MILITARY".** In OpenAIP, `type=4` is **CTR**
  (control zone), not military. Label/colour only — geometry and `icaoClass`
  are correct. One-line fix in the type→label map. *(claim: K2 airspace ·
  Correct)*

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
  *(claim: K2 airspace · Correct + Timely; revisit deferred — do NOT build yet)*

- **FAI / competition task editor — unverified.** The old suite showed failures
  around the FAI editor (`FAITaskUITest`, Chamonix/Monarca competition flows),
  likely one shared root cause in the editor labels/panel. Whether any was a
  real product bug vs. test debt is now **unverified** (the tests are gone).
  Re-validate the FAI task build/edit flow. *(claim: K6 route/task)*

- **Overlay tap / select on dense clusters.** *PG-spot tap is now wired*
  (2026-06): tapping a spot opens its weather/Flyability sheet via the
  maplibre-compose `SymbolLayer` `onClick`. **Still open:** reliable
  tap/select on *dense, overlapping* clusters (and other layers) where the
  collision-declutter and hit ambiguity bite — a real hit-test against the
  MapLibre projection (`cameraState.projection.positionFromScreenLocation`,
  as `OffScreenPeerIndicators` uses) is the durable fix. *(claim: K3 · Frictionless)*

- **~~Weather — deferred.~~ ✅ Done (2026-06).** Shipped as the full **K4
  weather / Flyability deck**, claim-tested — see [../claims.md](../claims.md)
  (K4) and [../design/flight-deck-ui.md](../design/flight-deck-ui.md). Open
  weather *gaps* are now narrow and tracked on the board: **thermal outlook**
  (climb-rate forecast) and the **Skew-T stability plot** (math exists, plot is
  a text placeholder). *(claim: K4 weather)*

## Not bugs (documented so they aren't re-investigated)

- **Mt Herman PG spot invisible at region zoom.** Expected: MapLibre
  `iconAllowOverlap=false` collision-declutter hides it when a denser nearby
  marker wins at low zoom. `inCache=2`, `inRenderedInventory=true`. No safety
  impact.
