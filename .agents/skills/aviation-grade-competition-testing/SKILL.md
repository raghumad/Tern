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
- **Waypoint Files (.wpt)**: Download the CompeGPS or OziExplorer waypoint file to get exact Lat/Lon and Altitude.
- **Task Definition**: Identify the sequence of waypoints and their specific competition roles.

### Deduction Checklist
| Competition Role | Key Information to Extract |
| :--- | :--- |
| **Launch (D01)** | Lat/Lon, Cylinder Radius (e.g., 400m), Altitude. |
| **Start Speed Section (SSS / SS)** | Cylinder Radius (usually large, e.g., 36km), Start Time, Direction (Enter/Exit). |
| **Turnpoints (TP)** | Lat/Lon, Cylinder Radius (e.g., 16km or 400m). |
| **End Speed Section (ESS)** | Radius, End Time, Relationship to Goal. |
| **Goal (GH / GOAL)** | Radius (e.g., 100m or 400m), Line vs. Cylinder. |

## 2. Modeling the Route

Map the deduced data to the app's `Route` and `Waypoint` models.

```kotlin
val monarcaRoute = Route(
    name = "Monarca 2026 - Task 1",
    waypoints = listOf(
        Waypoint("D01 Peñon", 19.0617, -100.0903, radius = 400f, type = "Launch"),
        Waypoint("SS E09 Ext9", 18.8380, -100.4317, radius = 36000f, type = "Start Speed Section"),
        // ...
        Waypoint("Goal A01", 19.0427, -100.1045, radius = 100f, type = "Goal")
    )
)
```

## 3. High-Fidelity Test Patterns

### The "Verifiable GIVEN" Pattern
Aviation tests must never "assume" the UI state. Every `GIVEN` step must be verified before proceeding to the `THEN` assertions.

**❌ BAD (Implicit Assumption):**
```kotlin
given("the pilot is at Peñon Launch") {
    givenAppIsLaunchedOnMap(lat = 19.06, lon = -100.09)
    zoomTo(19.06, -100.09)
}
```

**✅ GOOD (Verifiable GIVEN):**
```kotlin
given("the pilot is at Peñon Launch", takeScreenshot = true) {
    givenAppIsLaunchedOnMap(lat = 19.06167, lon = -100.09033, countryCode = "mx")
    zoomTo(19.06167, -100.09033, 12.0)
    showRouteOnMap(monarcaRoute)
    waitForMapToRender(3000)

    // PROVE the state is reached
    assertMapLocation(19.06167, -100.09033, tolerance = 0.01)
    assertZoomLevel(12.0)
    assertRoutePresence("Monarca 2026 - Task 1")
}
```

### Map Synchronization (Truthfulness)
Always sync the map camera to the target location. Showing competition waypoints over a default location (e.g., Boulder, CO) is a "Truthfulness" violation.
- Use `zoomTo(lat, lon, zoom)` immediately after `givenAppIsLaunchedOnMap`.
- Ensure the `countryCode` matches the competition region to trigger correct tile/airspace loading.

## 4. Telemetry Validation

Validate the HUD and waypoint list iteratively as the pilot "flies" the task.

1. **Distance to SSS**: Verify the distance matches the dedicated goal (e.g., 36km radius).
2. **Speed Section Transition**: Verify the HUD detects when the pilot enters/exits the Speed Section.
3. **Optimized Route**: Ensure the route lines are rendered from cylinder edge to cylinder edge, not just center to center.

## 5. Generic Test Template

Use the provided template in `example/GenericCompetitionTest.template.kt` to bootstrap new competition tests quickly while adhering to these standards.
