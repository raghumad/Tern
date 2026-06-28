# Known-issues — historical test snapshots (May 2026)

> **ARCHIVED.** Point-in-time clean-build snapshots from late May 2026,
> moved out of the active `docs/backlog/known-issues.md` to keep it current.
> Test counts here are stale by design — run `./gradlew testAll` (or
> `python3 scripts/test_report.py`) for current numbers. Kept for history.

## Snapshot: 2026-05-26 — post-scrub + single-map + package rename

Unit tests: 325 passed, 0 failed. Instrumented: 26 passed, 23 failed,
3 skipped, 19 @Liar. Package renamed to `com.ternparagliding`.

## Snapshot: 2026-05-25 — post-scrub on `fix-tests-and-scrub` branch

Unit tests: 338 passed, 0 failed (after PeerFeatureCollectionTest and
PeerReducerTest fixes). Instrumented tests not run in this snapshot
(no emulator).

5 instrumented tests tagged `@Untruthful` — they pass but their
assertions don't match their BDD step names.

New consolidated dashboard: `python3 scripts/test_report.py` from the
`tern-android/` directory.

---

## Snapshot: 2026-05-23 — fresh-machine clean build

Build target: `./gradlew testAll` on `mezulla` (4 commits ahead of
`revive-the-droid`).

**Build infrastructure works.** Dependencies resolved, Kotlin compiled
with zero errors, managed-device emulator (Pixel 9 Pro / API 35 / AOSP)
booted and ran tests, coverage report generated. Wall time: 1h 33m.

### Unit tests

```
Total: 115 (Expected: ~116)
Passed: 115
Failed: 0
Skipped: 0
```

⚠️ The test-counting harness flagged a "potential test runner crash" —
expected 116 tests from source, only 115 reported.

### Instrumented tests (managed Pixel 9 Pro / API 35)

```
Total: 54
Passed: 35
Failed: 19
Skipped: 0
Overall instruction coverage: 18.2%
```

### Failing instrumented tests (19)

**Route planning / management**
- `MapInteractionTest.testMapLongPressCreatesRoute`
- `RouteManagementTest.testReorderWaypoints`
- `RouteManagementTest.testShareRoute`
- `FAITaskUITest.testCreateCompleteCompetitionTask`
- `FAITaskUITest.testChangeWaypointTypeToSpeedSection`
- `FAITaskUITest.testConfigureFAITaskParameters`
- `AviationRoutePlanningTest.pilot_plans_mountain_record_attempt`
- `AviationRoutePlanningTest.pilot_adapts_route_to_weather_risk`
- `AviationRoutePlanningTest.pilot_imports_random_competition_task`
- `BirBillingCompetitionTest.pilot_flies_bir_billing_himalayan_odyssey`

**Hazards / overlays / map rendering (RFC005)**
- `ResourceAuditTest.auditHazardVisualFidelity`
- `UnifiedLocationTest.testHazardVisualizationRFC005`
- `UnifiedLocationTest.testLocationMarkerRFC005Compliance`
- `DenseClusterDeclutteringTest.testZoomToRouteAutomaticDecluttering`
- `DynamicMarkerTest.testPGSpotMarkerSwitchesToWindGauge`
- `PGSpotInteractionTest.pilot_scans_mountain_launch_for_safe_flying_conditions`

**Weather UX**
- `RouteDetailPanelWeatherTest.testWaypointWeatherDisplay`
- `WeatherUXTest.pilot_verifies_realtime_weather_at_lookout_mountain`
- `WeatherUXTest.pilot_identifies_atmospheric_hazards_in_alpine_terrain`

### What this means

The failure pattern: route planning barely works and doesn't render
accurately; airspace / hazard / marker rendering has performance and
correctness gaps. These are the regressions to address during the
eventual app-cleanup focus.
