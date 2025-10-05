# Concise Project Plan: osmdroid + Compose Integration Fix

## Context
Android paragliding app with osmdroid maps, Jetpack Compose UI, Redux architecture, and advanced caching (FlexBuffers + Hilbert indexing). Core issues: lifecycle management, memory leaks, state synchronization in Compose integration.

## Current Status (October 2025)
- **Phase 1: Redux Architecture & Core Lifecycle Fixes** ✅ 96% Complete
- **Phase 2: Enhanced Overlay Architecture** ✅ 97% Complete
- **Critical Bugs Fixed** ✅ All major issues resolved (UI blocking, cache persistence, ANR crashes)
- **Redux Compliance** ✅ 85% Complete (Settings migrated, global state management)

## Prioritized Remaining Tasks

### High Priority (Foundation & Stability)
- [ ] **Clean Up Legacy Code**: Remove `clearGeoJsonOverlays()`, consolidate overlay state in coordinator
- [ ] **Redux Migration Tasks**: Move permission state and map style to Redux for full compliance
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
