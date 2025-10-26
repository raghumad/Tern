# Code Mode Rules (Non-Obvious Only)

## Redux Implementation Patterns
- **State update batching**: MapStore.dispatch() batches actions (100ms window) - always use dispatch() instead of direct state mutation
- **Reducer composition**: MapState uses separate reducers (mapReducer, weatherReducer) - maintain this pattern for consistency
- **Action naming**: Use MapAction.* and WeatherActions.* - don't create new action types without updating reducers

## Overlay Manager Inheritance
- **Late store binding**: Always call setReduxStore() on overlay managers after construction - never pass store in constructor
- **Lifecycle management**: Override onOverlayAttached()/onOverlayDetached() - never override onAttach()/onDetach()
- **Memory-adaptive allocation**: Use getCurrentOverlayBudget() for overlay limits - don't hardcode overlay counts

## Coordinate System Handling
- **GeoJSON conversion**: Convert [longitude, latitude] to GeoPoint(latitude, longitude) - coordinate order swap is mandatory
- **Distance units**: Use miles for user display, convert to meters (×1609.34) for OSMDroid - don't mix units
- **Viewport filtering**: Combine distance-based (300-mile) and viewport culling - don't rely on single filtering method

## Error Handling Patterns
- **Memory error recovery**: Use MemoryErrorRecovery.getOverlayBudgetWithRecovery() - don't handle memory exceptions directly
- **Adaptive fallback**: Always use AdaptiveOverlayFallback.getFallbackOverlayBudget() as fallback - never fail completely
- **Performance monitoring**: Wrap performance-critical code with try/catch for PerformanceDebugger.record*() calls

## Caching Integration
- **Multiple cache managers**: Use AirspaceCache, PGSpotCache, PGSpotWeatherCache, UniversalCountryCacheManager - don't create new caches
- **Cross-country continuity**: Implement smooth cache handoff for border transitions - cache keys must include country context
- **Memory-aware sizing**: Cache sizes must adapt to flight phase and memory pressure - use adaptive allocation patterns

## Compilation Standards Enforcement
- **Zero warnings policy**: All code changes must compile without any warnings as a mandatory condition for marking any task as complete
- **Debug build checks**: Run `./gradlew assembleDebug` after code changes to ensure no warnings in debug builds
- **Release build checks**: Ensure `./gradlew assembleRelease` also compiles cleanly for production readiness
- **Integration with CI/CD**: Enforce zero warnings in all automated builds and pull request checks