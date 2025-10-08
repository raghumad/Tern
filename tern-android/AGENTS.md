# AGENTS.md

This file provides guidance to agents when working with code in this repository.

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

## Caching Strategy (Non-Obvious)
- **Multiple cache managers**: Separate caches for airspace (AirspaceCache), PG spots (PGSpotCache), weather (PGSpotWeatherCache), and countries (UniversalCountryCacheManager)
- **Cross-country continuity**: Country border transitions require smooth cache handoff to prevent visual discontinuities
- **Memory-aware caching**: Cache sizes adapt based on flight phase and memory pressure levels