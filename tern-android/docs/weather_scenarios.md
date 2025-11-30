# Weather & Safety Scenarios
> "Working backwards from the pilot's perspective"

## Scenario 1: Pre-Flight Analysis (The "Go/No-Go" Decision)
**Persona**: XC Pilot planning a 50km triangle.
**Goal**: Determine if the day is safe and flyable, and identify the best time to launch.

### Story
1.  **Pilot opens the app** and selects a planned route.
2.  **Pilot analyzes "Atmospheric Stability"**:
    *   **Goal**: View actionable metrics derived from atmospheric data (No complex charts).
    *   **Cloud Base**: "How high can I climb?" (LCL/CCL).
    *   **Inversion Layer**: "Is there a lid? How high is it? When will it break?"
    *   **Thermal Quality**: "What is the expected rate of climb?" (Derived from Lapse Rate).
3.  **Pilot checks "Overdevelopment Risk"**:
    *   **CAPE Timing**: "Is CAPE high *now*, or is it forecasted to peak at 3 PM?"
    *   **Decision**: "If CAPE > 1000 J/kg at 14:00, I must be on the ground by 13:30."
4.  **Decision**:
    *   If Inversion is at 1000m (Launch Height) -> "Check estimated break time based on surface heating."
    *   If Wind > 20km/h at ridge height -> "Too windy."

### Key Data Points
-   **Scope**: Data is fetched *only* for the Route's bounding box or the nearest PG spot.
-   **Trajectory-Based Analysis**: Weather is evaluated at each waypoint based on **estimated arrival time** (assuming avg speed, e.g., 25km/h).
    -   *Example*: If Launch is at 12:00 and Goal is 50km away, Goal weather is checked for 14:00.
-   **Derived Metrics**: Cloud Base (m), Inversion Height (m), Inversion Break Time (HH:mm), Est. Thermal Strength (m/s).
-   **Time-Series Data**: CAPE forecast (J/kg vs Time), Wind forecast (Speed/Dir vs Time/Alt).
-   **Visualization**:
    -   **Summary**: Shows the "Worst Case" risk along the entire route.
    -   **Breakdown**: Pilot can view details for specific waypoints (Launch, TP1, Goal).

## Scenario 2: Route Feasibility (The "Can I make it?" Decision)
**Persona**: Competition Pilot.
**Goal**: Optimize the route based on weather conditions.

### Story
1.  **Pilot views the route on the map**.
2.  **Pilot toggles "Wind Layers"**:
    *   Sees headwind on the first leg -> "Need to push speed bar".
    *   Sees tailwind on the final glide -> "Easy finish".
3.  **Pilot checks "XC Potential"**:
    *   Areas with good thermal activity are highlighted.
    *   "Blue holes" (no clouds/thermals) are marked.

### Key Data Points
-   **Wind Map**: Vector field overlay.
-   **XC Potential**: Thermal strength overlay.
-   **Cloud Cover**: % sky coverage.

## Scenario 3: In-Flight Safety (The "Get down safe" Decision)
**Persona**: Pilot in the air (Future Scope, but relevant for data modeling).
**Goal**: Avoid approaching storms or strong winds.

### Story
1.  **Pilot receives an alert**: "Storm approaching from SW".
2.  **Pilot checks "Rain Radar"**: Sees a cell developing 20km away.
3.  **Action**: Lands immediately.

---

## BDD Acceptance Criteria

### Feature: Skew-T Analysis
**Scenario**: Pilot checks stability at launch
**Given** a weather forecast for "Launch Site A"
**And** the forecast contains temperature and dewpoint profiles
**When** the pilot requests the Skew-T diagram
**Then** the system should calculate the "Lifted Index"
**And** the system should identify any "Inversion Layers"
**And** the system should estimate the "Cloud Base" height

### Feature: Overdevelopment Warning [Implemented in WeatherUXTest]
**Scenario**: High CAPE detected
**Given** a weather forecast for the route area
**And** the CAPE index is > 1500 J/kg
**When** the pilot views the route weather
**Then** the system should display a "High Thunderstorm Risk" warning

## UI Interaction: Weather Insights Panel

### Scenario: Pilot checks weather for an active route (Trajectory Analysis) [Implemented in RouteWeatherPanelTest]
**Given** the pilot has an active route loaded (Launch -> TP1 -> Goal)
**And** the estimated flight time is 3 hours
**When** the pilot taps the "Weather" floating action button
**Then** the "Weather Insights" bottom sheet should appear
**And** the header should say "Route Forecast (12:00 - 15:00)"
**And** the "Risk" section should show the **maximum risk** found along the route (e.g., if Goal has high risk at 15:00, show High Risk)
**And** the "Wind" section should show the average wind direction (e.g., "NW 15 km/h")

### Scenario: Pilot adjusts launch time
**Given** the "Weather Insights" panel is open
**And** the current launch time is set to "Now" (12:00)
**When** the pilot moves the "Launch Time" slider to "+1 hr" (13:00)
**Then** the "Route Forecast" header should update to "(13:00 - 16:00)"
**And** the risk and wind metrics should update based on the new trajectory arrival times
    -   Launch arrival: 13:00
    -   TP1 arrival: 14:30
    -   Goal arrival: 16:00

### Scenario: Pilot views waypoint breakdown
**Given** the "Weather Insights" panel is open
**When** the pilot taps on the "Wind" section
**Then** a detailed list should appear showing wind for each waypoint at its estimated arrival time:
    -   Launch (12:00): N 5 km/h
    -   TP1 (13:30): NW 10 km/h
    -   Goal (15:00): W 15 km/h

### Scenario: Pilot views high-risk weather warning [Implemented in WeatherUXTest]
**Given** the pilot has an active route
**And** the forecast indicates high CAPE (> 2000 J/kg)
**When** the pilot opens the Weather Insights panel
**Then** the "Risk" section should be highlighted in Red
**And** a warning text "High Overdevelopment Risk" should be visible
**And** the CAPE gauge should point to the Red zone

### Scenario: Pilot checks inversion break time
**Given** an inversion layer exists at 1500m
**And** the estimated break time is 13:00
**When** the pilot views the "Stability" section
**Then** it should display "Inversion: 1500m"
**And** it should show "Est. Break: 13:00" in Yellow (Caution)
