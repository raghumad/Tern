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
*   **Map Rendering**: MapLibre GL Native (`AndroidView(MapView)`) with
    vector tiles from OpenFreeMap. Local font rendering via
    `localIdeographFontFamilyEnabled(true)` — no glyph server needed
    (offline-first). OSMDroid was removed (8,400 lines deleted).
    *   **Dynamic Markers**: Peer markers are composite bitmaps rendered
        at runtime via Android `Canvas` (circle + pills + text + Nerd
        Font glyphs), registered in the MapLibre style as images, and
        displayed by a native `SymbolLayer` reading from a GeoJSON
        source. Each peer's visual is a single bitmap; MapLibre handles
        positioning, scaling, decluttering, and z-ordering natively.
    *   **GeoJSON-as-truth**: All map overlays (peers, airspaces, PG
        spots, routes) converge toward being GeoJSON features rendered
        by native MapLibre layers. This eliminates dual rendering
        systems (Compose + map) and makes collision detection, zoom
        scaling, and layer ordering free via MapLibre's built-in engine.

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
*   **Zero-Copy Deserialization**: Use of FlatBuffers (FlexBuffers) for performance.
*   **Spatial Indexing**: Hilbert Curve implementation for O(log N) range queries.
*   **Single Source of Truth**: Strict ownership of domain logic.

### Verification
*   **Claim-driven tests**: each pilot-facing promise is demonstrated by replaying a real flight and asserting observable behavior. See [../claims.md](../claims.md).
*   **Definition of Done**: unit + assemble green, and the served claim demonstrated.

## 5. Testing Strategy

The unit of testing is a **claim** — a promise Tern makes to a pilot
(offline-first, airspace safety, team awareness, graceful degradation), not a
screen. See [../claims.md](../claims.md) for the full matrix.

### 5.1 Unit tests (JVM)
*   Logic — Redux reducers, spatial queries, parsers, state machines — runs on the JVM via `./gradlew testAll` (`testDebugUnitTest` + `assembleDebug`). Fast, deterministic.

### 5.2 Claim-driven capability tests
*   Each claim is proven by replaying a real recorded flight (Aravis, Bir Billing) through the app's data/logic stack, controlling the environment (network on/off, fault injection), and asserting observable behavior — cache hits, store state, warnings fired — **not** screenshots.
*   The earlier emulator/BDD screenshot suite was removed; see [../archive/testing-bdd-suite-removed.md](../archive/testing-bdd-suite-removed.md).

## 6. Performance Monitoring

### 6.1 PerformanceDebugger
*   **Role**: Runtime monitor for performance anomalies.
*   **Metrics**:
    *   **State Update Rate**: Tracks Redux dispatch frequency. Uses **Exponential Moving Average (alpha=0.1)** to smooth out micro-bursts and avoid false positives.
    *   **Memory Pressure**: Monitors heap usage.
*   **Alerts**: Logs warnings (e.g., "STATE UPDATE STORM") if thresholds are exceeded (e.g., >3000 updates/sec sustained).
