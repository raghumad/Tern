# 🧠 MEMORY BANK: Tern Paragliding App

## 📋 Project Overview
- **Purpose**: Android paragliding app with aviation-grade safety standards for competition use (Red Bull X-Alps)
- **Tech Stack**: Kotlin, Jetpack Compose, osmdroid maps, Redux state management, FlexBuffers caching
- **Target Users**: Paragliders from student to competition pilots
- **Critical Focus**: Aviation safety, smooth performance, offline resilience

## 🎯 Core Architecture Patterns

### 1. Redux-First State Management
- **Single Source of Truth**: Redux Store manages all application state
- **Action → Reducer → State Flow**: Predictable state updates through actions
- **Component Observation**: UI components observe state with `.collectAsState()`
- **Performance Batching**: <10 Redux dispatches/sec target

### 2. Overlay Management System
- **BaseOverlayManager Pattern**: All overlays extend this class for Redux integration
- **Distance-Based Zoning**: 5-tier system (CORE → NEAR → MID → FAR → EXTREME)
- **Memory-Adaptive Allocation**: Dynamic budgets based on device capabilities
- **Hilbert Curve Ordering**: Beautiful center→outside overlay animations

### 3. Aviation Safety Standards
- **Progressive Enhancement**: App works at all sensor capability levels
- **GPS Validation**: All operations validated before execution
- **Smooth Transitions**: Zero jarring visual changes during flight
- **Visual Continuity**: Maintains situational awareness during border crossings

### 4. Performance Optimization
- **Spatial Querying**: Distance-based filtering, never load entire countries
- **Smart Caching**: FlexBuffers + Hilbert indexing for zero-copy queries
- **Background Processing**: All heavy work on `Dispatchers.IO`
- **Memory Limits**: <75% heap usage, adaptive overlay budgets

## 🗂️ Key Data Structures

### Redux State Hierarchy
```
MapState (Root)
├── userLocation: GeoPoint?
├── isLocationReady: Boolean
├── overlayState: OverlayState
│   ├── airspaces: OverlayConfig
│   ├── pgSpots: OverlayConfig
│   └── routes: OverlayConfig
├── weatherState: WeatherState
└── adaptiveLayout: AdaptiveLayoutConfig
```

### Overlay Zoning System
```
DistanceZone.CORE     (0-8km)  - Safety critical, never reduced
DistanceZone.NEAR     (8-40km) - High priority area awareness
DistanceZone.MID     (40-160km) - Regional context
DistanceZone.FAR    (160-320km) - Extended awareness
DistanceZone.EXTREME (320km+) - Remove under memory pressure
```

### Sensor Capability Levels
```
AccuracyLevel.AVIATION_GRADE  - Barometer + Gyroscope + GPS
AccuracyLevel.FLIGHT_GRADE    - GPS + Motion sensors
AccuracyLevel.MOBILE_GRADE    - GPS + Accelerometer only
AccuracyLevel.BASIC          - GPS fallback only
```

## 🗃️ Caching Architecture
- **AirspaceCache**: Country-based airspace data with Hilbert spatial indexing
- **PGSpotCache**: Paragliding spots with weather correlation
- **UniversalCountryCacheManager**: Smart 4-country limit for aviation use
- **RouteCache**: Flat buffer storage mimicking airspace patterns

## 🪂 Flight Phase Awareness
- **Launch**: High power, detailed airspace awareness
- **Thermal**: Circling, thermal sources and hazards
- **Glide**: Transition, clear path and landing options
- **Landing**: Approach, landing sites and obstacles

## 🎯 Paraglider-Specific Priorities
**🚨 PRIORITY 1: Never Reduce (Safety Critical)**
- Danger Areas, Restricted Areas, Temporary Restrictions, Parachute Zones

**⚠️ PRIORITY 2: High Priority (Flight Critical)**
- Training Areas, Competition Areas, Weather Avoidance, Thermal Sources

**📍 PRIORITY 3: Moderate Priority (Situational Awareness)**
- Controlled Airspace, Glider Sites, Terrain Hazards, Landing Options

**ℹ️ PRIORITY 4: Low Priority (Reduce First)**
- Airways, Reporting Points, Navigation Aids, Civil Airports

## 📊 Performance Targets
- **Redux Dispatches**: <10 per second
- **Memory Usage**: <75% heap
- **UI Performance**: 60fps smooth updates
- **Cache Hit Rate**: >80% for spatial queries
