# Known issues

A snapshot of failures observed on a clean build, kept here so we don't
lose track of regressions while focus is elsewhere. When focus shifts
from LoRa (mezulla) back to app cleanup, this is the starting list.

This file is **not** a to-do for now. We are not actively fixing
these. They are known and parked.

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
expected 116 tests from source, only 115 reported. One test is silently
missing. Worth understanding before this snapshot can be called clean
even on the unit side.

### Instrumented tests (managed Pixel 9 Pro / API 35)

```
Total: 54
Passed: 35
Failed: 19
Skipped: 0
Overall instruction coverage: 18.2%
```

### Failing instrumented tests (19)

Grouped roughly by feature area:

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

The failure pattern matches the user's prior framing: route planning
barely works and doesn't render accurately; airspace / hazard / marker
rendering has performance and correctness gaps. These are the
regressions that need addressing during the eventual app-cleanup focus.

### Reports

If anyone wants to look at the actual HTML test reports (they're not in
the repo — they're under build/ and gitignored), the build that
produced this snapshot wrote them to:

- `tern-android/app/build/reports/androidTests/managedDevice/debug/allDevices/index.html`
- `tern-android/app/build/reports/bdd-report/`
- `tern-android/app/build/reports/jacoco/jacocoTestReport/html/index.html`

These will be overwritten by the next build.
