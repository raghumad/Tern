---
name: Aviation-Grade Competition Testing
description: Methodology for deducing, modeling, and validating complex paragliding competition tasks with high-fidelity map synchronization and verifiable state transitions.
---

# Aviation-Grade Competition Testing

Competition tasks are the ultimate stress test for an aviation flight deck. This skill defines the protocol for moving from a real-world competition page (e.g., AirTribune) to a verifiable, truthful instrumented test.

## 1. Intelligence Gathering (Data Deduction)

Before writing a single line of test code, you must deduce the ground truth from competition resources.

### Sourcing the Task
- **AirTribune Info Page**: Look for the task definition (usually at the bottom) or the "Downloads" section.
- **Waypoint Files (.wpt)**: Download the waypoint file to get exact Lat/Lon and Altitude.
- **Task Definition**: Identify the sequence of waypoints and their specific competition roles (Launch, SSS, ESS, Goal).

## 2. Modeling the Route
Map the deduced data to the app's `Route` and `Waypoint` models. Ensure that radii and types are accurate to the competition rules (e.g., 400m vs 1000m).

## 3. High-Fidelity Test Patterns

### The "Verifiable GIVEN" Pattern
Aviation tests must never "assume" the UI state. Every `GIVEN` step must be verified before proceeding to the `THEN` assertions.
- **Map Synchronization**: Use `zoomTo(lat, lon, zoom)` immediately after launch.
- **Region Alignment**: Ensure the `countryCode` matches the competition region to trigger correct tile/airspace loading.

### Bypassing Smart Features
- **The Suggestion Blocking**: "Smart Suggestions" (nearby PG spots) often trigger dialogs when adding waypoints via long-press in tests.
- **Standard Protocol**: In BDD tests, avoid interactive long-press for route setup. Use `showRouteOnMap(route)` to inject the entire task via Redux. This ensures the test starts from a known, clean state without dialog interference.
- **Cleanup**: If a long-press MUST be used (to test the interaction itself), immediately use `composeTestRule.runOnUiThread` to dispatch `DeselectWaypoint` or `DeselectRoute` to clear any auto-generated edit screens.

## 4. Telemetry Validation
- **Strategic Auto-Minimize**: Always verify that the `RouteDetailPanel` starts in **SSA (Collapsed)** mode after task injection.
- **Tactical Review**: Use `performClick()` on the `SSA_Header` to expand to **TEA (Tactical)** mode for waypoint list verification.

## 5. Verification Checklist
- [ ] Is the map region correctly aligned to the task coordinates?
- [ ] Does the test bypass "Smart Suggestions" using Redux injection?
- [ ] Are all waypoint roles (SSS/ESS) correctly verified in TEA mode?
- [ ] Does the HUD show truthful metrics derived from the Redux state?
