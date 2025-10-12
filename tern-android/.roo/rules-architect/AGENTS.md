# Architect Mode Rules (Non-Obvious Only)

## Aviation Safety Constraints
- **Memory-adaptive architecture**: System must adapt overlay allocation based on flight phases and memory pressure - aviation safety critical
- **Emergency cleanup preservation**: Safety-critical overlays (airspace, weather) must always be preserved during memory cleanup
- **Performance-safety balance**: State update batching prevents UI freezes during flight - 100ms windows critical for responsiveness

## Redux State Architecture (Comprehensive Implementation)
- **Multi-store architecture**: MapStore (primary), RouteStore (route planning), WeatherStore (aviation weather) - each with specialized state management
- **Batching requirements**: MapStore must batch actions (100ms windows, max 10 actions) - prevents update storms during flight (>100/sec triggers performance warnings)
- **Late initialization pattern**: Overlay managers support late Redux store binding via setReduxStore() - enables flexible component lifecycle management
- **Aviation-specific state**: Include GPS status, overlay configurations, flight phase tracking, handedness preferences, adaptive UI layouts - not standard Redux patterns

## MapState Redux Implementation
- **Comprehensive state model**: Map viewport, location/GPS, overlays, weather, settings, sensors, flight data, flight computer, handedness-aware UI
- **Performance optimization**: Combined UpdateMapMovement action for batched map updates (rotation, center, zoom)
- **Safety-first design**: ReduxLocationService handles GPS through actions, validates aviation coordinates before enabling location services
- **State observation**: UI components use collectAsState() for reactive updates, no direct state manipulation

## RouteState Redux Implementation
- **FAI competition support**: Waypoint types (LAUNCH, TURNPOINT, LANDING, THERMAL), cylinder shapes, competition tasks (RACE_TO_GOAL, ELAPSED_TIME)
- **Safety checklist system**: Pre-flight validation with aviation categories (AVIATION, RESOURCES, ELECTRONICS, EMERGENCY)
- **Export functionality**: Multiple formats (XCTSK, GPX, KML, CUP, IGC, QR codes) for route sharing and FAI compliance
- **Offline capability**: Route cache management with download progress tracking and multi-tier caching

## Overlay Redux Integration
- **Memory-adaptive allocation**: Redux state drives overlay budgeting based on flight phases and memory pressure
- **Emergency cleanup system**: Aviation safety-critical overlay preservation during memory cleanup (airspace, weather overlays)
- **Three overlay types**: AIRSPACE, PG_SPOTS, ROUTES with individual OverlayConfig (enabled, opacity, labels, filter radius)
- **Weather integration**: Dedicated WeatherState and WeatherActions for PG spot weather data and display controls

## Redux Integration Patterns
- **ReduxLocationService**: Dedicated service handles GPS updates through Redux actions instead of direct state manipulation
- **Overlay manager integration**: BaseOverlayManager subscribes to Redux state changes via collectLatest, reacts to state updates
- **UI component pattern**: Components observe state with collectAsState(), dispatch actions for all state changes
- **Performance monitoring**: Debug-only tracking of Redux dispatch frequency with automatic dashboard reporting

## Redux Development Anti-Patterns & Solutions
- **❌ AVOID: Redux-first architecture**: Building Redux store/state/actions before working features - causes paralysis and failure
- **✅ CORRECT: Working system first**: Implement basic functionality with simple state management, then layer Redux incrementally
- **❌ AVOID: Big-bang Redux migration**: Converting entire app to Redux in one massive change - breaks everything
- **✅ CORRECT: Incremental Redux layering**: Add Redux bridge to sync with working simple store, gradually transfer responsibility

## Redux Migration Strategy
- **Phase 1**: Working simple store (WaypointStore) - immediate functionality
- **Phase 2**: Redux bridge - syncs Redux state with simple store changes
- **Phase 3**: Feature enhancement - add Redux features while maintaining simple store backup
- **Phase 4**: Gradual transfer - shift primary responsibility to Redux incrementally

## Redux Testing Architecture
- **State immutability testing**: Verifies reducers don't mutate original state
- **Action processing validation**: Tests that actions correctly update state
- **Configuration testing**: Validates default configurations and state updates
- **Reducer logic verification**: Direct testing of reducer functions for reliability
- **Bridge testing**: Validates sync between simple store and Redux state

## Overlay Management Architecture
- **Memory-based allocation**: AdaptiveOverlaySystem adjusts overlay budgets based on flight phases - critical for mobile aviation constraints
- **Inheritance hierarchy**: BaseOverlayManager provides common functionality - specific managers handle domain logic (airspace, routes, PG spots)
- **Debouncing strategy**: 500ms debounce for map movements - prevents excessive overlay reloading during flight maneuvers

## Coordinate System Architecture
- **Dual coordinate systems**: Handle both GeoJSON [longitude, latitude] and OSMDroid GeoPoint(latitude, longitude) - conversion mandatory
- **Distance-based filtering**: 300-mile default radius with viewport culling - optimizes performance for aviation use cases
- **Border transition handling**: Smooth cache handoff at country borders - maintains visual continuity during cross-country flights

## Performance Monitoring Architecture
- **Priority-based monitoring**: Track 7 specific priorities in debug builds - stripped completely in release for performance
- **Dashboard integration**: Automatic reporting every 30 seconds - triggered by location/map changes for real-time flight monitoring
- **Memory leak prevention**: Overlay lifecycle tracking with reference counting - critical for aviation app stability

## Caching Architecture Strategy
- **Multi-tier caching**: Separate specialized caches for airspace, PG spots, weather, countries - each optimized for aviation data patterns
- **Adaptive cache sizing**: Cache sizes must adapt to flight phases and memory pressure - maintains performance under constraints
- **Cross-border continuity**: Cache handoff mechanism for seamless transitions - prevents visual discontinuities during flight