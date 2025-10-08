# Debug Mode Rules (Non-Obvious Only)

## Performance Monitoring Setup
- **Debug-only overhead**: PerformanceDebugger only active in debug builds - all monitoring stripped in release (BuildConfig.DEBUG control)
- **Priority-based tracking**: 7 priorities tracked: state storms, memory leaks, duplicate loading, memory pressure, subscriptions, debouncing, API limits
- **Automatic reporting**: Dashboard logs every 30 seconds in logcat - trigger with location/map changes or manual PerformanceDebugger.triggerTestEvents()

## State Update Storm Detection
- **Storm threshold**: >100 updates/sec triggers warning - MapStore batches within 100ms windows (max 10 actions)
- **Performance scoring**: Dashboard shows 0-100 score - critical issues: state storms, memory pressure, visual discontinuities
- **Batch monitoring**: Use recordStateUpdate(actionCount) to track batch efficiency - don't call for individual actions

## Memory Leak Detection
- **Overlay lifecycle**: Call recordOverlayLifecycle(overlayType, isCreation) for every overlay - don't rely on garbage collection alone
- **Reference tracking**: MemoryMetrics tracks overlay objects vs cleared references - monitor for accumulation patterns
- **Memory pressure**: Record manually with recordMemoryPressure() - triggered automatically by AdaptiveOverlaySystem

## Border Transition Testing
- **Smooth transitions**: Use recordBorderTransition(fromCountry, toCountry, isSmooth) - visual discontinuities indicate cache issues
- **Country metrics**: Track border crossings vs smooth transitions - success rate should be >95% for good user experience
- **Cache continuity**: Visual discontinuities indicate cache handoff problems - check UniversalCountryCacheManager integration

## Emergency Cleanup Testing
- **Memory pressure simulation**: Monitor emergency cleanup triggers - aviation safety overlays always preserved during cleanup
- **Viewport efficiency**: Invisible overlay removal prioritizes memory efficiency - test with large overlay sets
- **Zone-based cleanup**: Distance zones cleared in reverse priority order - safety-critical zones preserved first