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
