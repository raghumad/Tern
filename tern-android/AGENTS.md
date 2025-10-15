# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## 🎯 CURRENT STATUS - October 2025

### ✅ COMPLETED (Phases 1-3, Most of Phase 4)
- **Redux Architecture & Performance**: 100% complete with 95%+ reduction in state updates
- **Overlay System**: Smart country management, smooth border transitions, universal caching
- **Critical Bugs**: All resolved (UI blocking, cache persistence, ANR crashes)
- **Aviation Safety**: Border cache issues resolved, continuous visual display during flight

### 🆕 RECENT PROGRESS (Oct 14, 2025)
- **Java runtime upgrade**: Project updated to target Java 21 (toolchain + kotlin jvmTarget). Local build verified on OpenJDK 21.
- **Route Planning — Architecture Analysis**: Comprehensive analysis completed of route planning implementation vs. architecture document. Core RouteStore, RouteColor, and RouteOverlayManager are excellently implemented (95% alignment), but UI layer is largely missing.
- **Critical Issues Identified**: 10 major bugs/unwanted behaviors identified including duplicate waypoint creation, missing route management UI, and broken route visualization.
- **Route Architecture Status**: Backend route management is production-ready, but user-facing route planning features need implementation.

### 🚧 REMAINING PHASE 4 (Items 5-6)
- **Item 5: Settings Reorganization** - Current: "Map Layers" + "Units" → Target: "Aviation → Display → Units → Help"
- **Item 6: User Guidance Enhancement** - No tooltip system found, basic onboarding exists

### 🚀 CURRENT PRIORITY: iOS Route Planning Integration
**Technical Foundation Ready:**
- ✅ Redux Infrastructure - Complete state management ready for route data
- ✅ Overlay Architecture - Mature system ready for route visualization
- ✅ Weather Algorithms - `WeatherRouter` and `RiskAssessmentEngine` complete
- ✅ Flight Computer - Advanced aviation calculations implemented

Note: Android Phase 1 MVP (waypoint creation/display) implemented and serves as a working checkpoint while iOS route planner is integrated in parallel.

## 🪂 PARAGLIDER-SPECIFIC OVERLAY PRIORITY SYSTEM

### Critical Safety Priorities (FAR 103 Ultralight Compliance)

**🚨 PRIORITY 1: Never Reduce (Safety Critical)**
- **Danger Areas**: Military zones, prohibited areas - immediate danger to paragliders
- **Restricted Areas**: Competition zones, nature reserves - legal restrictions
- **Temporary Restrictions**: TFRs, NOTAM areas - temporary flight hazards
- **Parachute Zones**: Drop zones - collision risk at similar altitudes

**⚠️ PRIORITY 2: High Priority (Flight Critical)**
- **Training Areas**: Glider training areas - shared airspace awareness
- **Competition Areas**: Race courses, turnpoints - active event zones
- **Weather Avoidance**: Storm cells, icing areas - immediate threats
- **Thermal Sources**: Known lift areas - flight optimization

**📍 PRIORITY 3: Moderate Priority (Situational Awareness)**
- **Controlled Airspace**: TMA, CTR - coordination requirements
- **Glider Sites**: Known thermal sources - flight planning
- **Terrain Hazards**: Power lines, towers - collision avoidance
- **Landing Options**: Suitable landing fields - safety planning

**ℹ️ PRIORITY 4: Low Priority (Reduce First)**
- **Airways**: Victor/Jet routes - not relevant for paragliders
- **Reporting Points**: VRP, Compulsory points - powered aircraft only
- **Navigation Aids**: VOR, NDB stations - not used by paragliders
- **Civil Airports**: Commercial airports - avoid but not critical

## 📱 SENSOR HARDWARE INTEGRATION GUIDE

### Device Sensor Availability Matrix
| Device Series | Barometer | Gyroscope | Accelerometer | Magnetometer |
|---------------|-----------|-----------|---------------|--------------|
| **Pixel 1-3** | ✅ | ✅ | ✅ | ✅ |
| **Pixel 4-5** | ✅ | ✅ | ✅ | ✅ |
| **Pixel 6-9** | ❌ | ❌ | ✅ | ⚠️ Partial |
| **Pixel 10+** | ❓ | ❓ | ✅ | ❓ |

### Sensor Graceful Degradation Architecture
```
With Barometer (Best Case):
GPS Altitude → Barometer → Kalman Filter → Aviation Calculations → Redux State
     ↓              ↓            ↓              ↓                   ↓
±50m raw      ±5m pressure   ±3m filtered   Flight Computer     UI Display
```

```
Without Barometer (GPS Only):
GPS Altitude → Kalman Filter → Aviation Calculations → Redux State
     ↓              ↓              ↓                   ↓
±50m raw       ±20m filtered  Flight Computer     UI Display
```

## Redux State Management (Non-Obvious Patterns)
- **State update batching**: MapStore dispatches actions in batches (100ms window, max 10 actions) to prevent update storms (>100/sec triggers performance warnings)
- **Aviation-specific state**: Redux state includes GPS status, overlay configurations, and flight phase tracking for memory-based adaptive allocation
- **Late store binding**: Overlay managers support late Redux store initialization via setReduxStore() for flexible lifecycle management

## Overlay Manager Architecture (Non-Obvious Patterns)
- **Memory-adaptive allocation**: BaseOverlayManager uses AdaptiveOverlaySystem for flight-phase and memory-pressure-based overlay budgeting
- **Emergency cleanup system**: Triggered by memory pressure with aviation safety preservation (safety-critical overlays preserved during cleanup)
- **Debounced map moves**: 500ms debounce delay prevents excessive overlay reloading during map interactions

## Coordinate System Conventions (Non-Obvious)
- **GeoJSON conversion**: Always convert GeoJSON [longitude, latitude] format to OSMDroid GeoPoint(latitude, longitude) - coordinate order swap required
- **Distance calculations**: Use miles for user-facing measurements, convert to meters (×1609.34) for OSMDroid calculations
- **Viewport filtering**: Distance-based filtering (300-mile default) combined with viewport culling for performance optimization

## Performance Monitoring (Non-Obvious)
- **Priority-based metrics**: PerformanceDebugger tracks 7 priorities: state storms, memory leaks, duplicate loading, memory pressure, subscriptions, debouncing, API limits
- **Debug-only overhead**: All performance monitoring stripped in release builds (BuildConfig.DEBUG control)
- **Automatic reporting**: Dashboard logs every 30 seconds in debug mode with performance scores and critical issue alerts

## 🏗️ Overlay Architecture (Non-Obvious)

### Distance-Based Zoning System
- **5-tier spatial hierarchy**: CORE(0-8km) → NEAR(8-40km) → MID(40-160km) → FAR(160-320km) → EXTREME(320km+)
- **Memory-adaptive allocation**: Zone budgets dynamically adjust based on device memory (50-400 overlays)
- **Aviation safety preservation**: CORE zone (safety-critical overlays) never reduced by memory pressure
- **Flexible redistribution**: Unused zone budget automatically flows to zones with available overlays

### Memory-Based Adaptive Management
- **Device class optimization**: Automatic adaptation across device memory classes (2GB → 8GB+ RAM)
- **Real-time memory monitoring**: Uses Android ActivityManager APIs for memory pressure detection
- **Emergency cleanup system**: Triggered by memory pressure with aviation safety preservation
- **Performance improvement**: 2.3x overlay utilization improvement (36→85 overlays with same budget)

### Hilbert Curve Spatial Ordering
- **Beautiful animations**: Overlays animate from center→outside using Hilbert curve mathematics
- **Visual continuity**: Intuitive spatial progression creates wave-like animation effects
- **Batch processing**: 10-overlay batches with 100ms stagger delays for smooth rendering
- **Consistent ordering**: Same algorithm used for both addition and removal operations

### Caching Strategy (Non-Obvious)
- **Multiple cache managers**: Separate caches for airspace (AirspaceCache), PG spots (PGSpotCache), weather (PGSpotWeatherCache), and countries (UniversalCountryCacheManager)
- **Cross-country continuity**: Country border transitions require smooth cache handoff to prevent visual discontinuities
- **Memory-aware caching**: Cache sizes adapt based on flight phase and memory pressure levels
- **Unified validation system**: 3-layer validation (source → feature → cache integrity) prevents corruption

### Zoom-Based Filtering
- **Dynamic overlay density**: 12 overlays (low zoom) → 100 overlays (high zoom)
- **Performance optimization**: Reduces rendering load at low zoom levels
- **Distance-based priority**: Closer overlays prioritized at all zoom levels
- **Smooth transitions**: No jarring changes when zooming in/out

### Batch Processing for Performance
- **Redux action batching**: Multiple overlay operations grouped into single state updates
- **Animation staggering**: 100ms delays between overlay additions for smooth visual flow
- **Memory efficiency**: Batch processing reduces individual animation overhead
- **State update optimization**: <10 Redux dispatches/sec during overlay operations

## ⚡ STRICT COMPLETION CRITERIA (Mandatory for ALL Development)

### Technical Verification (Mandatory)
- [ ] **Code Quality**: Zero compilation errors or warnings
- [ ] **Redux Architecture**: 100% Redux compliance, no legacy patterns
- [ ] **Performance Targets**: <10 Redux dispatches/sec, <75% memory usage
- [ ] **Safety Standards**: Zero visual discontinuity during flight operations
- [ ] **No Regression**: Existing functionality unaffected

### User Experience Verification
- [ ] **Problem Resolution**: Original issue completely solved
- [ ] **Progressive Enhancement**: Works for all user types (Student → Competition pilot)
- [ ] **Clear Benefit**: Improvement obvious to users
- [ ] **Aviation Safety**: Standards maintained (smooth transitions, no UI blocking)

## 🏗️ ARCHITECTURE PRINCIPLES & ENFORCEMENT (Non-Negotiable)

### Core Architecture (MANDATORY)
- **Hybrid Development Pattern**: New features MAY use simple state management during development, MUST migrate to Redux before release
- **Route Planner Exception**: Route planner implementation uses ViewModel-based state management initially, migrates to Redux in Phase 2
- **Aviation Safety First**: Smooth transitions, predictable performance, offline resilience
- **Spatial Optimization**: Hilbert indexing, intelligent country caching (max 4 countries)
- **Performance Monitoring**: Debug-only tracking with zero release impact

### ✅ DO (Hybrid Development Pattern)
- **Phase 1 (Development)**: Use ViewModel-based state management for rapid prototyping
- **Phase 2 (Polish)**: Migrate to Redux pattern with overlay managers extending `BaseOverlayManager`
- Use Redux actions for all state changes after migration (`store.dispatch(Action)`)
- Coordinate through `OverlayCoordinator` for overlay lifecycle after migration
- Follow single responsibility principle per overlay type
- Use Redux state observation after migration (`store.state.collect { }`)

### ⚠️ ALLOWED (During Development Phase)
- ✅ ViewModel-based state management for new features
- ✅ Direct overlay manipulation in ViewModels during development
- ✅ Simple state management patterns for rapid prototyping
- ✅ Direct UI component to overlay manager communication during development
- ✅ Duplicate state management patterns during migration transition

### ❌ FORBIDDEN (Production Patterns)
- ❌ Release features with non-Redux state management
- ❌ Skip Redux migration after development phase
- ❌ Complex business logic in UI components
- ❌ Direct database/network access from UI components

## 🚀 REDUX MIGRATION STRATEGY

### Migration Phases
1. **Phase 1: Working simple store** - Implement basic functionality with ViewModel state management
2. **Phase 2: Redux bridge** - Create Redux actions that sync with simple store changes
3. **Phase 3: Feature enhancement** - Add Redux features while maintaining simple store backup
4. **Phase 4: Gradual transfer** - Shift primary responsibility to Redux incrementally

### Migration Criteria (When to Start Phase 2)
- ✅ **Core functionality works** - All planned features implemented and tested
- ✅ **User experience validated** - Feature works well with simple state management
- ✅ **Performance requirements met** - <10 dispatches/sec equivalent, <75% memory usage
- ✅ **Code stability achieved** - Zero crashes, minimal bugs, confident in implementation

### Migration Anti-Patterns to Avoid
- ❌ **Redux-first architecture**: Building Redux store/state/actions before working features
- ❌ **Big-bang Redux migration**: Converting entire feature to Redux in one massive change
- ❌ **Parallel development**: Building both simple and Redux versions simultaneously

### Testing During Migration
- **State immutability testing**: Verify Redux reducers don't mutate original state
- **Action processing validation**: Test that Redux actions correctly update state
- **Bridge testing**: Validate sync between simple store and Redux state
- **Performance regression testing**: Ensure migration doesn't impact performance

## 🎯 SUCCESS METRICS (Mandatory for ALL Features)

### Core Technical Standards
- **Performance**: <10 Redux dispatches/sec, <75% memory usage
- **Safety**: Zero visual discontinuity during border crossings
- **Competition Ready**: Handle X-Alps routes with <3 country downloads total
- **Code Quality**: Zero compilation errors, zero warnings
- **Architecture**: 100% Redux compliance, no legacy patterns

### iOS Route Planning Integration (Current Priority)
- **Route Planning**: Functional waypoint management with FAI compliance
- **QR Sharing**: Working QR code system matching iOS functionality
- **Weather Integration**: Visual weather-optimized route display
- **Performance**: <10 Redux dispatches/sec, <75% memory usage maintained

## 🧭 Roadmap & Immediate Next Steps (Oct 10, 2025)

### Current checkpoint
- Phase 1 (MVP) Android: Long-press waypoint creation, immediate marker rendering, and simple in-memory `WaypointList` UI implemented and pushed to branch `revive-the-droid` (commit `ee626cc`). Local Kotlin compile passes and quick device sanity check completed.
- iOS reference: Existing SwiftUI `RoutePlannerModel` and `RoutePlannerMapViewHelper` (provided by the author) used as design/behavior reference for exports (GPX/XCTSK/CUP), long-press handling, marker styling, and polyline redraw logic.

### Short-term prioritized work (FAI MVP)
1. Waypoint types: add `LAUNCH`, `TURNPOINT`, `LANDING` to the `Waypoint` model; UI for type selection on creation/edit. (Estimate: 1 day)
2. Polyline route visualization: implement `RouteOverlayManager` to draw geodesic polylines between waypoints and update on change. (Estimate: 0.5–1 day)
3. Editing & ordering: marker dragging and list reorder; update overlays and labels on change. (Estimate: 1–2 days)
4. Exports: port iOS export functions (start with GPX then XCTSK/QR and CUP). (Estimate: 1–2 days)

### Implementation constraints
- Keep changes minimal per Phase (1–3 files where possible).
- Zero compile warnings required before pushing to GitHub.
- Device test after each incremental change.
- Redux migration for route state to follow only after Phase 1 editing features are stable (Phase 2).

### How the iOS code maps to Android work
- `RoutePlannerModel.mapView` lifecycle & delegate logic → `MapViewContainer` + `MapViewModel` coordination on Android.
- `addWaypoint(coordinate:)` (iOS) → long-press handler in Android `MapViewContainer` (already implemented).
- Export routines (`saveXCTSKqr()`, `saveCompegpsWpt()`, `saveGPX`-style helpers) on iOS provide canonical serialization formats to port.

## 🚧 ROUTE PLANNING IMPLEMENTATION STATUS (Oct 14, 2025)

### ✅ EXCELLENT: Backend Architecture (95% Complete)
**RouteStore.kt**: Production-ready with StateFlow management, multi-route support, visibility control
**RouteColor.kt**: Aviation-appropriate styling with 8 color options and waypoint marker colors
**RouteOverlayManager.kt**: Reactive rendering with memory-adaptive performance management
**Route.kt**: Complete data model with metadata, timestamps, validation

### ❌ CRITICAL GAPS: User Interface (0% Complete)
**Missing Route Management UI**: No interface to create, edit, or manage routes
**Broken Route Visualization**: RouteOverlayManager exists but not connected to main map view
**Global Waypoint Storage**: Still using old pattern instead of route-centric approach

### 🎯 ROUTE PLANNING TODO LIST (Implementation Priority)

#### **CRITICAL BUGS** 🚨
- [ ] **Fix duplicate waypoint creation** in MapViewContainer.kt (creates waypoints twice)
- [ ] **Connect RouteOverlayManager** to main map view for route visualization

#### **CORE FUNCTIONALITY** ⚡
- [ ] **Create RouteManagerUI.kt** for route management interface
- [ ] **Implement route-centric waypoint storage** (replace global WaypointStore pattern)
- [ ] **Add route creation and editing UI flows**

#### **ENHANCED FEATURES** 🔧
- [ ] **Create route visibility and selection controls** (multi-route management)
- [ ] **Add route statistics and validation display** (distance, waypoint count, etc.)
- [ ] **Implement route import/export functionality** (GPX, XCTSK, CUP formats)
- [ ] **Add route persistence** across app restarts

#### **FUTURE: REDUX MIGRATION** 🏗️
- [ ] **Connect Redux actions to UI components** (after core functionality validated)

### 📋 IMPLEMENTATION STRATEGY
1. **Start with critical bug fixes** - Get existing functionality working properly
2. **Implement core route management** - Essential user-facing features
3. **Add enhanced features** - Better user experience and data management
4. **Redux migration last** - Only after everything works and is validated

### 🎯 SUCCESS CRITERIA FOR ROUTE PLANNING
- [ ] **Multiple routes** can be displayed simultaneously with independent visibility
- [ ] **Route editing** provides instant visual feedback during waypoint dragging
- [ ] **Route visibility** can be controlled independently per route
- [ ] **Real-time updates** work smoothly with <10 state updates/sec
- [ ] **No performance degradation** with multiple routes and waypoints
- [ ] **Backward compatibility** maintained during implementation

## 🚨 CRITICAL BUGFIXES (Do Not Repeat)
- **Performance**: Move ALL processing to `Dispatchers.IO` (prevents ANR crashes)
- **Architecture**: Delay map operations until GPS fix (prevents invalid API calls)
- **Cache Management**: Never auto-clear caches on startup (preserves offline capability)
- **Spatial Queries**: Always use `queryNearbyFeatures()` only (never entire countries)
