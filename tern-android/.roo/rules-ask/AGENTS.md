# Ask Mode Rules (Non-Obvious Only)

## Project Context Discovery
- **Aviation-specific patterns**: Tern is a paragliding app - all features relate to aviation safety, GPS tracking, and airspace awareness
- **Memory constraints**: Mobile aviation app with limited memory - performance optimization critical for flight safety
- **Redux state complexity**: MapStore manages aviation-specific state (GPS, overlays, flight phases) - not standard web Redux

## Architecture Understanding
- **Overlay inheritance hierarchy**: BaseOverlayManager → specific managers (Airspace, PGSpot, Route) - understand inheritance for proper extension
- **Adaptive systems**: Memory-based overlay allocation adapts to flight phases - crucial for understanding performance constraints
- **Coordinate complexity**: GeoJSON [lng,lat] vs OSMDroid GeoPoint [lat,lng] - coordinate system differences cause runtime errors

## Performance Analysis Context
- **Debug-only monitoring**: PerformanceDebugger stripped in release - debug builds have extensive monitoring overhead
- **Priority-based tracking**: 7 specific performance priorities tracked - focus analysis on state storms, memory leaks, border transitions
- **Aviation safety constraints**: Emergency cleanup preserves safety-critical overlays - performance issues can affect flight safety

## Caching Strategy Context
- **Multiple specialized caches**: Separate cache managers for different data types - each optimized for specific aviation data patterns
- **Cross-border continuity**: Smooth cache handoff at country borders - critical for seamless user experience during flight
- **Memory-adaptive sizing**: Cache sizes change based on flight phases and memory pressure - affects data availability

## Error Recovery Context
- **Graceful degradation**: Memory pressure triggers fallback budgets - system continues operating with reduced functionality
- **Emergency cleanup**: Aviation safety overlays always preserved - cleanup prioritizes safety over performance
- **Adaptive fallbacks**: Multiple fallback mechanisms for overlay allocation - system maintains core functionality under constraints