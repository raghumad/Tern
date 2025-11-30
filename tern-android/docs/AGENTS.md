# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## 🎯 CURRENT STATUS - November 2025

### ✅ COMPLETED: Full Route Planner Implementation
- **Route Planner MVP**: Complete multi-waypoint route creation with Redux architecture
- **Waypoint Selection**: Interactive editing with visual highlighting and state management
- **Redux Architecture**: 100% Redux compliance with proper Action → Reducer → State → UI flow
- **Code Quality**: Comprehensive refactoring completed - constants extracted, imports cleaned, MapState.kt split
- **Testing Foundation**: Ready for automated testing framework implementation

### 🆕 RECENT PROGRESS (November 2025)
- **Route Planner Complete**: Full implementation with waypoint creation, selection, and Redux state management
- **Code Quality Overhaul**: 6-file pattern review, 15+ magic numbers eliminated, MapState.kt split into 4 focused files
- **Interactive Editing**: Waypoint selection with visual feedback and state management
- **Architecture Cleanup**: Constants extraction, import optimization, clear naming conventions established

### 🚀 CURRENT PRIORITY: Testing Framework & Quality Assurance
**Quality Foundation Ready:**
- ✅ **Code Refactoring Complete**: All magic numbers eliminated, imports cleaned, file organization optimized
- ✅ **Interactive Features**: Waypoint selection working with proper Redux state management
- ✅ **Architecture Solid**: Clean separation of concerns, established patterns followed
- ✅ **Documentation Updated**: Coding guidelines enhanced with naming conventions

**Next Phase**: Automated testing framework to ensure quality as we add advanced features

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

## 🧭 Current Status & Next Steps (November 2025)

### ✅ COMPLETED: Route Planner MVP with Redux Architecture
- **Full Route Implementation**: Multi-waypoint routes with Redux state management
- **Interactive Editing**: Waypoint selection with visual highlighting and state management
- **Code Quality**: Comprehensive refactoring - constants extracted, MapState.kt split, naming conventions established
- **Architecture**: Clean separation of concerns, established patterns followed throughout

### 🎯 IMMEDIATE NEXT STEPS: Testing Framework Setup
**Priority**: Establish automated testing before adding advanced features

#### **Phase 8.2: Testing Framework** (Current Priority)
- [ ] **Test Dependencies**: Add JUnit, Espresso, Mockito to build.gradle.kts
- [ ] **Unit Test Structure**: Create app/src/test/kotlin/ directory structure
- [ ] **Redux Logic Tests**: Test reducers and actions (highest ROI)
- [ ] **Route Model Tests**: Test Route.kt business logic
- [ ] **Integration Tests**: Test component interactions
- [ ] **UI Test Setup**: Configure Espresso for gesture testing
- [ ] **CI/CD Integration**: Add GitHub Actions workflow

### 🚀 FUTURE: Advanced Route Features (After Testing)
#### **Phase 7.1: Interactive Editing**
- [x] **Waypoint Selection**: Tap to select/deselect with visual highlight
- [ ] **Waypoint Deletion**: Long press selected waypoint to delete
- [ ] **Drag & Drop**: Move waypoints with live visual feedback
- [ ] **Waypoint Types**: LAUNCH, TURNPOINT, LANDING with visual indicators

#### **Phase 7.3: Route Management UI**
- [ ] **Route List Screen**: View and manage multiple routes
- [ ] **Route Statistics**: Distance, flight time, waypoint counts
- [ ] **Import/Export**: GPX, XCTSK, CUP format support
- [ ] **Cross-Platform**: iOS compatibility and data sharing

### 📋 **IMPLEMENTATION PRINCIPLES** (Validated & Working)
1. **✅ UX-First Development**: Working features before architectural complexity
2. **✅ Incremental Progress**: Small, testable changes with immediate validation
3. **✅ Code Quality First**: Refactoring and testing before new features
4. **✅ Aviation Safety**: Performance standards and smooth transitions maintained
5. **✅ Zero Warnings**: Clean compilation throughout development

### 🎯 **SUCCESS CRITERIA FOR CURRENT PHASE**
- [ ] **Automated Testing**: Unit tests for Redux logic and business logic
- [ ] **Quality Assurance**: Regression prevention through automated validation
- [ ] **CI/CD Pipeline**: Automated testing on code changes
- [ ] **Confidence Building**: Safe foundation for advanced feature development

## 🚧 ROUTE PLANNING IMPLEMENTATION STATUS (October 2025)

### ✅ **ROUTE-CENTRIC ARCHITECTURE**: Implementation Strategy Complete
- **✅ Unified Documentation**: route_planner.md created with airspace cache-like persistence
- **✅ Route-Centric Design**: Routes own waypoints, 10-route limit, Hilbert spatial indexing
- **✅ UX-First Strategy**: Working system first, Redux polish second (validated approach)

### ✅ **BACKEND ARCHITECTURE**: Production-Ready (95% Complete)
**RouteStore.kt**: StateFlow management with multi-route support and visibility control
**RouteColor.kt**: Aviation-appropriate styling with 8 color options and waypoint marker colors
**RouteOverlayManager.kt**: Reactive rendering with memory-adaptive performance management
**Route.kt**: Complete data model with metadata, timestamps, validation

### 🚧 **CURRENT IMPLEMENTATION**: UX-First Approach (In Progress)
**Phase 1: Critical Bug Fixes & Core UX** (Started)
- ✅ **Implementation Plan**: UX-first with Redux migration after validation
- 🔄 **Phase 1A**: Fix duplicate waypoint creation in MapViewContainer.kt
- ⏳ **Phase 1B**: Connect RouteOverlayManager to main map view for route visualization
- ⏳ **Phase 1C**: Create RouteManagerUI.kt for route management interface

### 🎯 **IMPLEMENTATION ROADMAP**: UX-Validated Development

#### **Phase 1: Critical Bug Fixes & Core UX** (Week 1-2)
- [x] **Implementation Plan**: UX-first approach confirmed
- [ ] **Fix duplicate waypoint creation** in MapViewContainer.kt (creates waypoints twice)
- [ ] **Connect RouteOverlayManager** to main map view for route visualization
- [ ] **Create RouteManagerUI.kt** for route management interface
- [ ] **Implement route-centric waypoint storage** (replace global WaypointStore pattern)

#### **Phase 2: Enhanced UX Features** (Week 3-4)
- [ ] **Add route creation and editing UI flows**
- [ ] **Create route visibility and selection controls** (multi-route management)
- [ ] **Add route statistics and validation display**
- [ ] **Implement route import/export functionality** (GPX, XCTSK, CUP formats)

#### **Phase 3: Redux Integration** (Week 5+)
- [ ] **Redux migration only after UX is validated and working perfectly**
- [ ] **Connect Redux actions to UI components** (following established migration strategy)

### 📋 **IMPLEMENTATION PRINCIPLES**
1. **✅ UX-First Development**: Get user experience working before Redux complexity
2. **✅ Working System First**: Implement basic functionality with simple state management
3. **✅ Incremental Redux**: Add Redux bridge only after UI interactions work perfectly
4. **✅ Aviation Safety**: Maintain smooth transitions and performance standards
5. **✅ Zero Warnings**: Ensure code quality throughout development

### 🎯 **SUCCESS CRITERIA FOR PHASE 1**
- [ ] **Zero duplicate waypoints** created on map interactions
- [ ] **Route visualization** working with immediate visual feedback
- [ ] **Route Management UI** functional for create/edit/delete operations
- [ ] **Route-centric data model** implemented (routes own waypoints)
- [ ] **Real-time UI updates** during route editing
- [ ] **Performance maintained**: <10 state updates/sec, <75% memory usage

## 🗂️ Route-Centric Architecture Patterns

### Route Persistence Strategy (AirspaceCache-Like)
- **RouteCache**: Mimics AirspaceCache with flat buffer storage and Hilbert indexing
- **10-Route Limit**: Maximum stored routes with distance-based spatial filtering
- **Centroid Calculation**: Routes indexed by waypoint bounding box centroid
- **Memory-Mapped I/O**: Zero-copy route loading for performance
- **300-Mile Queries**: Default spatial query radius for route discovery

### Route Data Ownership
- **Routes Own Waypoints**: Strong data relationships (routes contain waypoint collections)
- **No Global WaypointStore**: Waypoints exist only within route context
- **Automatic Cleanup**: Route deletion removes all associated waypoints
- **Type Safety**: Compile-time guarantees of waypoint-route relationships

### Route State Management
- **Phase 1**: RouteCache with ViewModel-based operations
- **Phase 2**: Redux bridge syncs RouteCache with Redux state
- **Incremental Migration**: RouteCache remains for performance-critical operations
- **Hybrid Pattern**: Best of both worlds - Redux for UI, RouteCache for persistence

## 🚨 CRITICAL BUGFIXES (Do Not Repeat)
- **Performance**: Move ALL processing to `Dispatchers.IO` (prevents ANR crashes)
- **Architecture**: Delay map operations until GPS fix (prevents invalid API calls)
- **Cache Management**: Never auto-clear caches on startup (preserves offline capability)
- **Spatial Queries**: Always use `queryNearbyFeatures()` only (never entire countries)

## File Modification Preferences
- **Use diff and patch**: For all file modifications, use Linux `diff` and `patch` commands via `execute_command` to ensure precise editing.
