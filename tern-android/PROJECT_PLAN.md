# Concise Project Plan: osmdroid + Compose Integration Fix

## Context
Android paragliding app with osmdroid maps, Jetpack Compose UI, Redux architecture, and advanced caching (FlexBuffers + Hilbert indexing). Core issues: lifecycle management, memory leaks, state synchronization in Compose integration.

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

## Prioritized Remaining Tasks

### High Priority (Foundation & Stability)
- [x] **Clean Up Legacy Code**: Remove `clearGeoJsonOverlays()`, consolidate overlay state in coordinator ✅ COMPLETE
- [x] **Redux Migration Tasks**: Move permission state and map style to Redux for full compliance ✅ COMPLETE
- [ ] **Performance Validation**: Test API rate limits, memory efficiency, smooth transitions
- [ ] **Failure Recovery System**: Implement API fallback, network resilience, cache expiration for weather

### Medium Priority (Feature Completion)
- [ ] **Complete WeatherDetailsScreen Implementation**: Implement composable for weather popup details (PGSpots/weather features)
- [ ] **Phase 3: Advanced Features**: Sensor integration (accelerometer, compass, GPS), 3D terrain, sensor fusion, aviation features

### Low Priority (Polish & Testing)
- [ ] **Phase 4: Testing & Polish**: Device testing, performance profiling, bug fixes, documentation

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
