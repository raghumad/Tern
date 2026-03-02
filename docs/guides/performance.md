# Tern Android Performance Benchmarks

This document describes the aviation-grade performance benchmarking system implemented for the Tern paragliding application.

## Overview

The performance benchmark suite ensures that critical aviation operations maintain strict performance guarantees required for safety-critical applications. All benchmarks are designed to prevent performance regressions that could compromise pilot safety.

## Safety-Critical Performance Targets

| Component | Target | Rationale |
|-----------|--------|-----------|
| Redux Dispatch Rate | < 10ms per batch | Prevents UI freezing during rapid state updates |
| Memory Usage | < 75% heap | Ensures sufficient memory for emergency operations |
| GPS Processing | < 5ms per update | Critical for real-time navigation accuracy |
| UI Responsiveness | < 16ms per frame | Maintains 60 FPS for smooth flight displays |
| Cache Operations | < 2ms per operation | Fast data retrieval for navigation |

## Benchmark Categories

### 1. Redux Dispatch Benchmarks (`ReduxDispatchBenchmark.kt`)
- Measures state update performance under load
- Tests batching effectiveness
- Validates concurrent operation handling
- **Safety Standard**: DO-178C Level C
- **Recent Fixes**: Resolved "State Update Storm" false positives by implementing:
    - **State Batching**: Grouping updates within 100ms windows.
    - **Burst Smoothing**: Using Exponential Moving Average (alpha=0.1) for rate calculation to ignore micro-bursts.

### 2. Memory Usage Benchmarks (`MemoryBenchmark.kt`)
- Monitors heap usage during data operations
- Tests memory recovery mechanisms
- Validates cache memory patterns
- **Safety Standard**: DO-178C Level B

### 3. GPS Operation Benchmarks (`GpsOperationBenchmark.kt`)
- Measures GPS data processing speed
- Tests coordinate transformation performance
- Validates aviation coordinate validation
- **Safety Standard**: DO-178C Level A

### 4. UI Responsiveness Benchmarks (`UiResponsivenessBenchmark.kt`)
- Tests Compose UI rendering performance
- Measures state change propagation
- Validates touch interaction responsiveness
- **Safety Standard**: DO-178C Level C

### 5. Performance Regression Monitor (`PerformanceRegressionMonitor.kt`)
- Detects performance degradation over time
- Validates against established baselines
- Generates safety compliance reports
- **Safety Standard**: DO-178C Level B

### 6. Performance Baseline (`PerformanceBaseline.kt`)
- Establishes reference performance metrics
- Validates DO-178C Level B compliance
- Comprehensive safety validation
- **Safety Standard**: DO-178C Level B

## Running Benchmarks

### Local Development

1. **Build benchmark APK:**
   ```bash
   cd tern-android
   ./gradlew assembleBenchmark
   ```

2. **Install and run on device:**
   ```bash
   # Install APK
   adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk

   # Run benchmarks
   adb shell am instrument -w com.madanala.tern.benchmark/androidx.benchmark.junit4.AndroidBenchmarkRunner
   ```

3. **Automated script:**
   ```bash
   python3 scripts/run_performance_benchmarks.py
   ```

### Local Development Only

Benchmarks run locally on development machines only. No automated CI/CD integration.

**Local Script:**
```bash
python3 scripts/run_performance_benchmarks.py
```

## Performance Reports

All benchmark results are saved to `app/build/reports/benchmarks/`:

- `comprehensive_performance_report.json` - Detailed metrics
- `safety_compliance_report.json` - Compliance validation
- `performance_trends.json` - Trend analysis
- `baselines/` - Baseline performance metrics
- `benchmark_output.txt` - Raw benchmark output

## Gradle Tasks

- `runPerformanceBenchmarks` - Show benchmark information
- `runBenchmarkBuild` - Build benchmark APK
- `generatePerformanceReports` - Generate performance reports

## Aviation Safety Compliance

All benchmarks validate against RTCA DO-178C aviation software certification standards:

- **Level A**: GPS operations (catastrophic failure prevention)
- **Level B**: Memory management, regression monitoring
- **Level C**: Redux dispatch, UI responsiveness

### Compliance Validation

The system automatically validates that all performance metrics meet safety standards. If any benchmark fails to meet the required thresholds, the build will fail with a clear error message indicating which safety standard was violated.

## Baseline Establishment

Performance baselines are established using statistical analysis:
- Average performance over multiple runs
- 95th percentile (P95) validation
- Standard deviation analysis
- Trend detection for gradual degradation

## Performance Regression Detection

The system continuously monitors for performance regressions:

1. **Immediate Validation**: Each benchmark run checks against thresholds
2. **Trend Analysis**: Detects gradual performance degradation
3. **Baseline Comparison**: Validates against established performance baselines
4. **Safety Alerts**: Generates alerts when safety standards are violated

## Device Testing Requirements

For accurate aviation-grade performance measurement:

- **Physical Device**: Benchmarks must run on actual Android devices (not emulators)
- **Performance Mode**: Device should be in high-performance mode
- **Clean State**: Device should have minimal background processes
- **GPS Hardware**: Real GPS hardware required for GPS benchmarks

## Contributing

When adding new features:

1. Add corresponding performance benchmarks
2. Update safety thresholds if needed
3. Ensure benchmarks validate against appropriate DO-178C levels
4. Update baseline metrics after performance optimization

## Troubleshooting

### Common Issues

1. **Benchmark Timeout**: Increase timeout in CI configuration
2. **Device Not Found**: Ensure Android device is connected and authorized
3. **Memory Issues**: Check device has sufficient free memory
4. **GPS Benchmarks Fail**: Ensure GPS hardware is available and enabled

### Benchmark Debugging

Enable verbose logging:
```bash
adb logcat | grep "Benchmark\|Performance"
```

## Performance Optimization Guidelines

Based on benchmark results, optimize:

1. **Redux Operations**: Minimize state update frequency
2. **Memory Usage**: Implement efficient caching strategies
3. **GPS Processing**: Optimize coordinate transformations
4. **UI Rendering**: Reduce recomposition frequency
5. **Cache Performance**: Use appropriate data structures

## Future Enhancements

- Automated performance regression alerting
- Historical performance trend visualization
- Device-specific performance profiling
- Memory leak detection integration
- GPU performance benchmarking for map rendering