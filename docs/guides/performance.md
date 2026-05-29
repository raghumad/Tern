# Tern Android Performance Benchmarks

## Overview

The performance benchmark suite checks that critical operations stay within acceptable latency and memory budgets. Regressions here affect pilot experience (choppy map, delayed position updates).

## Performance Targets

| Component | Target | Rationale |
|-----------|--------|-----------|
| Redux Dispatch Rate | < 10ms per batch | Prevents UI freezing during rapid state updates |
| Memory Usage | < 75% heap | Leaves headroom for map tiles and mesh events |
| GPS Processing | < 5ms per update | Real-time navigation accuracy |
| UI Responsiveness | < 16ms per frame | 60 FPS for smooth flight displays |
| Cache Operations | < 2ms per operation | Fast data retrieval for navigation |

## Benchmark Categories

### 1. State Update Benchmarks
- Measures state update performance under load.
- Validates batching effectiveness (100ms window).
- Uses **PerformanceDebugger.kt** to detect "State Update Storms" (>3000/sec).

### 2. Memory Usage Monitoring
- Monitors heap usage during data operations.
- Validates memory budget compliance.

### 3. GPS & Spatial Performance
- Measures processing speed for Hilbert indices and coordinate transformations.
- Validates coordinate validation latency (< 5ms).

## PerformanceDebugger

All benchmark results are reported via **Logcat** when `BuildConfig.DEBUG` is enabled. `PerformanceDebugger` provides periodic summaries including:
- Redux updates/sec
- Active allocations (by type)
- Estimated bytes in memory-mapped buffers

## Running Benchmarks

### Local Development

1. **Build benchmark APK:**
   ```bash
   cd tern-android
   ./gradlew assembleBenchmark
   ```

2. **Install and run on device:**
   ```bash
   adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
   adb shell am instrument -w com.madanala.tern.benchmark/androidx.benchmark.junit4.AndroidBenchmarkRunner
   ```

Benchmarks run locally on development machines only. No CI integration.

## Gradle Tasks

- `runPerformanceBenchmarks` - Show benchmark information
- `runBenchmarkBuild` - Build benchmark APK
- `generatePerformanceReports` - Generate performance reports

## Device Testing Requirements

For accurate performance measurement:

- **Physical Device**: Benchmarks should run on actual Android devices (not emulators)
- **Performance Mode**: Device should be in high-performance mode
- **Clean State**: Minimal background processes
- **GPS Hardware**: Real GPS hardware required for GPS benchmarks

## Debugging

Enable verbose logging:
```bash
adb logcat | grep "Benchmark\|Performance"
```

## Optimization Guidelines

Based on benchmark results, optimize:

1. **Redux Operations**: Minimize state update frequency
2. **Memory Usage**: Implement efficient caching strategies
3. **GPS Processing**: Optimize coordinate transformations
4. **UI Rendering**: Reduce recomposition frequency
5. **Cache Performance**: Use appropriate data structures
