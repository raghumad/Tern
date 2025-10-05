# Concise Project Plan: osmdroid + Compose Integration Fix

## Context
Android paragliding app with osmdroid maps, Jetpack Compose UI, Redux architecture, and advanced caching (FlexBuffers + Hilbert indexing). **Critical Issue Identified**: Country border cache clearing causes dangerous visual discontinuities during flight, especially problematic in Europe and during competitions like Red Bull X-Alps.

## Current Status (October 2025)
- **Phase 1: Redux Architecture & Core Lifecycle Fixes** ✅ 100% Complete
- **Phase 2: Enhanced Overlay Architecture** ✅ 100% Complete
- **Critical Bugs Fixed** ✅ All major issues resolved (UI blocking, cache persistence, ANR crashes, overlay display)
- **Redux Compliance** ✅ 100% Complete (All state migrated: permissions, map style, overlays, settings)
- **Legacy Code Cleanup** ✅ 100% Complete (Deprecated methods removed, modern architecture)

## Recent Fixes (October 2025)
- **PG Spots Display Bug**: Fixed hardcoded empty list in `loadPGSpotsForCurrentLocation()` - now properly queries and displays nearby paragliding sites ✅
- **Map Jitter Reduction**: Increased debounce delays (map movement: 500ms→1000ms, cleanup: 1000ms→2000ms) and added debouncing to Redux state updates to prevent excessive UI recompositions ✅
- **Cancellation Exception Handling**: Fixed improper error logging of job cancellations in PGSpotCache - now re-throws CancellationException for proper propagation ✅
- **Overlay Architecture Completion**: PG spots and airspaces now display correctly with optimized performance

## 🚨 Critical Aviation Safety Issue Identified
**Country Border Cache Clearing**: Current system clears entire country caches when crossing borders, causing:
- **Visual discontinuity** during critical flight phases ❌
- **Performance spikes** from full country reloads ❌
- **Safety hazard** from jarring map changes ❌
- **Poor UX** especially in dense border areas like Europe ❌

**Impact Assessment**: This affects aviation safety during competitions like Red Bull X-Alps where pilots cross multiple borders.

## Updated Priority System (Aviation Safety First)

### PRIORITY 0: Critical Aviation Safety (Border Cache Issue) 🚨
- [ ] **Smart Country Management**: Replace hard country clearing with intelligent multi-country caching
- [ ] **Flight Path Prediction**: Preload countries along predicted flight path (X-Alps, cross-country)
- [ ] **Spatial Query Optimization**: Query within countries using Hilbert indexing for performance
- [ ] **Smooth Border Transitions**: Eliminate visual discontinuity when crossing borders

### PRIORITY 1: Performance Validation & Optimization (Debug-Only)
- [ ] **Debug Performance Monitor**: BuildConfig-gated performance validation system
- [ ] **State Update Storm Fix**: Redux workflow optimization with batching
- [ ] **Memory Leak Detection**: Proper cleanup and lifecycle management
- [ ] **Request Deduplication**: Prevent duplicate network/spatial requests

### PRIORITY 2: Enhanced Features & Polish
- [ ] **WeatherDetailsScreen Implementation**: Complete weather popup details composable
- [ ] **Advanced Aviation Features**: Sensor integration, 3D terrain, sensor fusion
- [ ] **Testing & Documentation**: Device testing, performance profiling, user docs

## Technical Solution Architecture

### Universal Overlay Country Management
**Problem**: Country border cache clearing affects ALL overlay types (airspaces, PG spots, weather), causing visual discontinuity during flight

**Solution**: Universal country management system that serves ALL overlay managers with intelligent caching and smooth transitions

#### Three-Layer Architecture

**Layer 1: Universal Country Management** (Core Infrastructure)
```kotlin
UniversalCountryCacheManager {
    // Single cache manager for ALL overlay types
    - Manages country data universally
    - Handles flight path prediction
    - Coordinates all overlay managers
    - Provides shared country state to Redux
}
```

**Layer 2: Overlay-Specific Logic** (Specialized Processing)
```kotlin
// Each overlay manager focuses on its specific responsibility
AirspaceOverlayManager {
    // Uses universal country cache
    // Specializes in airspace rendering & queries
}

PGSpotOverlayManager {
    // Uses universal country cache
    // Specializes in PG spot data & display
}

WeatherOverlayManager { // Future
    // Uses universal country cache
    // Specializes in weather data integration
}
```

**Layer 3: Redux State Integration** (Unified State)
```kotlin
// Single Redux state for all overlay country management
OverlayState {
    activeCountries: Set<String>
    cachedCountries: Map<String, CountryMetadata>
    flightPath: FlightPath?
    perOverlayTypeState: Map<OverlayType, TypeState>
}
```

#### Universal Country Strategy
- **Max cached countries**: 3-4 total (memory efficient across all overlay types)
- **Flight path prediction**: Preload countries along predicted flight path (X-Alps, cross-country)
- **Distance-based retention**: Keep recent countries, remove distant ones
- **Spatial queries**: Use Hilbert indexing within countries for all overlay types
- **Smooth transitions**: Universal transition management across all overlays

#### Benefits for All Overlay Types

**Airspace Overlays**:
- ✅ Smooth border transitions with continuous airspace display
- ✅ No airspace disappearance during critical flight phases
- ✅ Intelligent preloading of adjacent country airspace

**PG Spot Overlays**:
- ✅ Continuous display of paragliding sites across borders
- ✅ No loss of nearby PG spots during border crossing
- ✅ Smart caching of popular cross-border PG areas

**Weather Overlays** (Future):
- ✅ Weather data continuity across borders
- ✅ No weather information gaps during flight
- ✅ Larger radius weather caching for aviation needs

**Any Future Overlays**:
- ✅ Automatic integration with country management system
- ✅ Consistent behavior across all overlay types
- ✅ Shared flight path prediction and caching logic

#### Aviation Safety Compliance
- **Visual Continuity**: No jarring transitions for any overlay type during flight
- **Performance Predictability**: Consistent behavior across airspaces, PG spots, and weather
- **Competition Ready**: Handle X-Alps route with <3 country downloads total for all overlay types
- **Memory Efficiency**: Universal country management prevents duplicate caching across overlay types

### Performance Validation System (Debug-Only)
**Approach**: BuildConfig.DEBUG-gated monitoring with zero release impact

**Priority Order**:
1. State update storm → Redux batching optimization
2. Memory leaks → Reference tracking and cleanup
3. Duplicate loading → Request deduplication cache
4. Memory pressure → Viewport-based cleanup
5. Subscription overhead → State change batching
6. Insufficient debouncing → Aviation-appropriate timing (1000ms+)
7. API rate limiting → Background throttling

## Risk Assessment
- **High Risk**: Border cache issue impacts aviation safety ❌
- **Medium Risk**: Performance issues affect user experience ❌
- **Low Risk**: Feature completion can be incremental ✅

## Success Metrics
- **Aviation Safety**: Smooth border transitions, no visual discontinuity
- **Performance**: <100 state updates/sec, <75% memory usage, <10 API calls/min
- **User Experience**: No jarring transitions, predictable loading, responsive UI
- **Competition Ready**: Handle X-Alps route with <3 country downloads total

## Lessons Learned: Critical Bugfixes (Do Not Repeat)

### Performance Fixes
- **UI Blocking Fix**: Move ALL airspace processing (21MB NDGeoJSON parsing) to `Dispatchers.IO` to prevent ANR crashes
- **Background Processing**: All downloads, parsing, file I/O must use background threads with `withContext(Dispatchers.IO)`
- **Cache Persistence**: Never auto-clear caches on startup/reload - preserve offline capability for aviation use

### Architecture Fixes
- **Initialization Order**: Always delay map operations until GPS fix instead of validating coordinates to prevent invalid API calls
- **Overlay Clearing**: Use overlay manager clearing instead of broad `clearGeoJsonOverlays()` to avoid unintended side effects
- **Redux Store Connection**: Ensure managers are connected to Redux store before checking `isEnabled()` state
- **Hilbert Index**: Use `entry.hilbertIndex` directly from spatial index, not from feature JSON
- **Spatial Queries**: Always query nearby features only, never entire countries for performance (`queryNearbyFeatures()` only)

### Data Handling Fixes
- **Resource Management**: Always use `.use {}` for file/buffer operations to prevent leaks
- **Cache Deserialization**: Support multiple format fallbacks for backward compatibility
- **Duplicate Downloads**: Single source of truth per data type - overlay managers handle all loading

### Future Guideline
Aggressively clean up legacy/unused code to maintain code health and prevent accumulation of technical debt.

## Key Architecture Decisions
- Keep osmdroid for offline aviation use
- Redux for global state management
- Overlay managers for independent lifecycle handling
- Spatial-first caching with Hilbert indexing
- Aviation-grade resilience (pilots never stranded)

## Risk Assessment
Low risk - building on solid foundation. Incremental validation, backward compatible.
