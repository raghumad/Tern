# Tern Android Architecture

## 1. Core Philosophy
**Offline-First & Safety-Centric**: The app is designed for paragliding pilots who often operate in areas with poor connectivity. All critical data (Routes, Weather, Airspaces) must be available offline and accessible with low latency.

## 2. System Architecture

### 2.1 State Management
*   **Pattern**: Redux-like Unidirectional Data Flow.
*   **Components**:
    *   **Store**: Single source of truth.
    *   **Actions**: Describe state changes (e.g., `RouteAction.AddWaypoint`).
    *   **Reducers**: Pure functions that compute new state.
    *   **Middleware**: Handles side effects (Logging, Analytics).

#### 2.1.1 Performance Optimization (State Batching)
*   **Problem**: High-frequency actions (e.g., GPS updates, slider drags) can trigger excessive recompositions ("State Update Storms").
*   **Solution**: `MapStore` implements a **Batching Mechanism**.
    *   **Logic**: Actions are queued and processed in chunks (max 100ms window or 50 actions).
    *   **Benefit**: Reduces UI recomposition rate from ~3000/sec to <60/sec during bursts, maintaining 60fps smoothness.

### 2.2 UI Layer
*   **Framework**: Jetpack Compose (100% Kotlin).
*   **Navigation**: Compose Navigation.
*   **Map Rendering**: OSMDroid with custom Overlays (`RouteOverlayManager`).
    *   **Dynamic Markers**: Uses `Compose` -> `Bitmap` generation for live data visualization (e.g., Wind Gauges) on the map.

#### 2.2.1 ViewToBitmap Utility
*   **Purpose**: Renders Composables into Bitmaps for use as Map Markers.
*   **Technical Detail**: Attaches a `ComposeView` to a parent `ViewGroup` (usually `MapView`) to inherit the Window's `LifecycleOwner`, `ViewModelStoreOwner`, and `SavedStateRegistryOwner`. This ensures Composables have a valid environment to run animations and state logic before being drawn to a Canvas.

## 3. Data & Caching Strategy

### 3.1 Serialization
*   **Technology**: **FlatBuffers** (via `FlexBuffers`).
*   **Rationale**: Zero-copy deserialization allows accessing data directly from memory-mapped files without parsing overhead. Crucial for loading large datasets (Airspaces, Weather) quickly.

### 3.2 Spatial Indexing
*   **Technology**: **Hilbert Curve**.
*   **Implementation**: Maps 2D coordinates (Lat/Lon) to a 1D integer.
*   **Benefit**: Preserves locality. Nearby points in 2D space are close in the 1D index, enabling extremely fast range queries (e.g., "Find all weather forecasts within 50km").

## 4. Weather Feature Architecture

### 4.1 Data Source
*   **Provider**: Open-Meteo API.
*   **Key Metrics**: Wind (80m & Gusts), CAPE (Risk), Cloud Base, Inversion.
*   **Units**: Aviation standard (Knots, Feet/Meters).

### 4.2 Trajectory Analysis (4D)
*   **Concept**: Forecasts are not static; they depend on *when* the pilot arrives at a waypoint.
*   **Logic**: `TrajectoryAnalyzer` calculates the estimated arrival time at each waypoint and fetches the weather forecast for that specific time and location.

### Data Strategy
*   **Zero-Copy Deserialization**: Use of FlatBuffers (FlexBuffers) for performance. [Skill: Spatial Efficiency](file:///home/raghu/src/Tern/.agents/skills/spatial-efficiency/SKILL.md)
*   **Spatial Indexing**: Hilbert Curve implementation for O(log N) range queries. [Skill: Spatial Efficiency](file:///home/raghu/src/Tern/.agents/skills/spatial-efficiency/SKILL.md)
*   **Single Source of Truth**: Strict ownership of domain logic. [Skill: Source of Truth](file:///home/raghu/src/Tern/.agents/skills/source-of-truth/SKILL.md)

### Verification
*   **BDD Testing**: Behavior-driven scenarios for pilot workflows. [Skill: BDD UI Testing Fidelity](file:///home/raghu/src/Tern/.agents/skills/bdd-ui-testing-fidelity/SKILL.md)
*   **Definition of Done**: Mandatory safety checks before merge. [Skill: Aviation-Grade DoD](file:///home/raghu/src/Tern/.agents/skills/aviation-grade-dod/SKILL.md)

## 5. Testing Strategy

### 5.1 Instrumentation
*   **Configuration**: `ANDROIDX_TEST_ORCHESTRATOR` is **DISABLED**.
*   **Rationale**: Running all UI tests in a single process allows state (Redux Store, In-Memory Cache) to persist between tests. This enables **Composable Scenarios** where one test sets up the state for the next, mimicking long user sessions.

### 5.2 BDD Framework
*   **DSL**: Custom `BddTest` class with `given`, `when`, `then` syntax.
*   **Global Steps**: Reusable setup functions (e.g., `givenAppIsLaunchedOnMap`) to reduce boilerplate.
*   **Thumb Rule for New Features**: **EVERY** new feature or significant component must be accompanied by a BDD-style automated test verifying its core user-facing scenarios. Manual verification should only be a supplementary check, not the primary validation method.
*   **The "Story" Principle**: Every BDD scenario must include a clear **Story** that defines *who* is using the feature, *what* their context is, and *why* it matters (e.g., "As a pilot on launch without cell service, I need..."). This ensures development remains deeply aligned with the pilot's UX and safety principles.

## 6. Performance Monitoring

### 6.1 PerformanceDebugger
*   **Role**: Runtime monitor for performance anomalies.
*   **Metrics**:
    *   **State Update Rate**: Tracks Redux dispatch frequency. Uses **Exponential Moving Average (alpha=0.1)** to smooth out micro-bursts and avoid false positives.
    *   **Memory Pressure**: Monitors heap usage.
*   **Alerts**: Logs warnings (e.g., "STATE UPDATE STORM") if thresholds are exceeded (e.g., >3000 updates/sec sustained).
