# Architect Mode Rules (Non-Obvious Only)

## Aviation Safety Constraints
- **Memory-adaptive architecture**: System must adapt overlay allocation based on flight phases and memory pressure - aviation safety critical
- **Emergency cleanup preservation**: Safety-critical overlays (airspace, weather) must always be preserved during memory cleanup
- **Performance-safety balance**: State update batching prevents UI freezes during flight - 100ms windows critical for responsiveness

## Redux State Architecture
- **Batching requirements**: MapStore must batch actions (100ms windows, max 10 actions) - prevents update storms during flight
- **Late initialization pattern**: Overlay managers support late Redux store binding - enables flexible component lifecycle management
- **Aviation-specific state**: Include GPS status, overlay configurations, flight phase tracking - not standard Redux patterns

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