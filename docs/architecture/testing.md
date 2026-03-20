# Tern Android App - Comprehensive Testing Guide

## Overview

The Tern Android app implements a comprehensive testing framework with conditional execution modes. The main command `runAllTestsAndGenerateSummary` provides flexible testing capabilities that can scale from basic unit tests to full aviation-grade validation.

## Testing Architecture

### Core Components
- **Unit Tests**: JUnit 5 with business logic validation
- **Instrumentation Tests**: Espresso + Jetpack Compose for UI testing
- **Performance Benchmarks**: Aviation safety compliance validation
- **Coverage Analysis**: JaCoCo with quality thresholds
- **Regression Testing**: Trend analysis and automated monitoring
- **Unstable Test Isolation**: Mechanism to run flaky tests separately to prevent blocking the main suite.

### Unstable Test Isolation
- **Annotation**: `@Unstable` (marks tests as flaky/crashing).
- **Script**: `run_tests_safely.sh`
    - **Pass 1**: Runs all stable tests.
    - **Pass 2**: Runs unstable tests (if Pass 1 succeeds).
- **Benefit**: Ensures a crash in a flaky test doesn't prevent reporting for the rest of the suite.

### Test Automation & CI/CD
Instrumentation tests are primarily driven by Gradle and the `./device` script (which supports fuzzy matching). advanced Python-based trend analysis and automated SDK management are planned for future CI integration but are not part of the local development suite.

## Usage

### Basic Testing (Fast Development)
```bash
# Unit tests only with coverage report
./gradlew runUnitTestsOnlyAndGenerateSummary

# Standard testing with unit + instrumentation tests
./gradlew runAllTestsAndGenerateSummary
```

### Performance Testing Mode
```bash
# Include aviation performance benchmarks
./gradlew runAllTestsAndGenerateSummary -PincludePerformanceTests=true
```
- Validates Redux dispatch rates (< 10ms target)
- Monitors memory usage (< 75% heap target)
- Tests GPS processing performance (< 5ms target)
- Checks UI responsiveness (< 16ms target)

### Regression Testing Mode
```bash
# Include regression analysis and trend monitoring
./gradlew runAllTestsAndGenerateSummary -PincludeRegressionTests=true
```
- Generates coverage trend reports
- Performs regression detection
- Updates coverage history database
- Provides trend analysis with recommendations

### Full Automation Mode
```bash
# Include zero-step automated testing
./gradlew runAllTestsAndGenerateSummary -PincludeFullAutomation=true
```
- Downloads/configures Android SDK (if needed)
- Creates/manages test emulator
- Runs complete test suite automatically
- Generates comprehensive reports
- Cleans up resources automatically

### Combined Testing (Production Ready)
```bash
# Complete testing pipeline for production validation
./gradlew runAllTestsAndGenerateSummary \
  -PincludePerformanceTests=true \
  -PincludeRegressionTests=true \
  -PincludeFullAutomation=true
```

## Execution Modes Summary

| Mode | Property | Purpose | Duration | Dependencies |
|------|----------|---------|----------|--------------|
| **Standard** | (default) | Unit + instrumentation tests with coverage | 5-15 min | Device/emulator required |
| **Performance** | `includePerformanceTests=true` | Aviation safety benchmarks | +10-30 min | Device + Python 3.6+ |
| **Regression** | `includeRegressionTests=true` | Trend analysis and regression detection | +2-5 min | Python 3.6+ |
| **Full Automation** | `includeFullAutomation=true` | Zero-step automated testing | +20-60 min | Python 3.6+ + Android SDK |
| **Combined** | All properties | Complete production validation | 30-120 min | All dependencies |

## Reliability & Determinism

To ensure a 100% pass rate in the instrumentation suite, we employ several deterministic synchronization techniques:

### 1. Proactive Cache Clearing
- **Issue**: Stale disk caches (Mapbox, Airspace) can cause race conditions or inconsistent initial states.
- **Fix**: `MapVisualTest` proactively deletes these caches using `InstrumentationRegistry` *before* the activity launches.

### 2. Multi-Stage Log Synchronization (`waitForLogMatching`)
- **Issue**: `waitForIdle()` and `waitForMapToRender()` may not account for asynchronous background tasks completing (e.g., data ingestion).
- **Fix**: Use `ReportGenerator.waitForLogMatching(tag, regex)` to block until a specific internal state is logged. This is essential for complex data flows like Airspace rendering.

### 3. Permission-Aware Launching
- **Issue**: `GrantPermissionRule` can trigger activity restarts on newer Android APIs (e.g., API 35) if permissions are granted after `setContent`.
- **Fix**:
    - Added `GrantPermissionRule` to `MapVisualTest` to automatically grant `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`.
    - Use the `givenAppIsLaunchedOnMap()` helper to ensure mocking and initialization occur before the Compose hierarchy is tested.

## Visual Regression Review

The framework includes a Python-based visual reviewer to manage screenshot "goldens" and bad states.

### 1. Starting the Reviewer
After running tests, start the local review server:
```bash
python3 scripts/visual_reviewer.py
```
This serves the test reports on `http://localhost:8080`.

### 2. Approving/Rejecting Snapshots
Open the test report in your browser and use the interactive buttons:
- **✅ Approve as Golden**: Copies the screenshot to `assets/goldens/`. Future tests will fail if they don't match this state.
- **❌ Wrong / Reject**: Blacklists the current screenshot hash in `blacklist.json`. Future tests will fail if they produce this specific "bad" state (e.g., unrendered map tiles).

### 3. Troubleshooting
- **Server Not Starting**: Ensure `build/reports/androidTests/managedDevice/debug/allDevices` exists (run `./gradlew testAll` first).
- **CORS Errors**: If buttons don't work, check if `visual_reviewer.py` is running on the expected port (8080).
- **Stuck Loading**: Refresh the page or restart the Python server.

## Output Files

### Always Generated
- `build/reports/test-summary.md`: Comprehensive test summary
- `build/reports/jacoco/combined/html/index.html`: Coverage report

### Performance Mode
- `app/build/reports/benchmarks/`: Performance benchmark results
- `app/build/reports/benchmarks/safety_compliance_report.json`: Safety validation

### Regression Mode
- `coverage-trend-report.md`: Trend analysis report
- `.coverage_history.json`: Historical coverage data

### Automation Mode
- `build/reports/automation/`: Automation execution logs

## Quality Gates

### Coverage Thresholds
- **Instruction Coverage**: 75% minimum
- **Branch Coverage**: 70% minimum
- **Method Coverage**: 75% minimum
- **Class Coverage**: 85% minimum
- **Safety-Critical Packages**: 90% minimum

### Performance Targets
- **Redux Dispatch**: < 10ms per operation
- **Memory Usage**: < 75% heap utilization
- **GPS Processing**: < 5ms per update
- **UI Responsiveness**: < 16ms frame time

## Fallback Behavior

All optional components gracefully degrade when dependencies are missing:

- **Missing Python**: Skips advanced analytics, uses basic reports
- **Missing Device**: Skips instrumentation tests, continues with unit tests
- **Missing Scripts**: Falls back to basic Gradle-built functionality
- **Network Issues**: Continues with local-only testing capabilities

## CI/CD Integration

### GitHub Actions Example
```yaml
- name: Run Standard Tests
  run: ./gradlew runAllTestsAndGenerateSummary

- name: Run Full Validation (Nightly)
  run: |
    ./gradlew runAllTestsAndGenerateSummary \
      -PincludePerformanceTests=true \
      -PincludeRegressionTests=true \
      -PincludeFullAutomation=true
  if: github.event.schedule == '0 2 * * *'  # Nightly at 2 AM UTC
```

### Quality Gate Enforcement
```yaml
- name: Quality Gate Check
  run: |
    ./gradlew jacocoTestCoverageVerification
    python scripts/check_coverage_threshold.py
```

## Troubleshooting

### Common Issues

**Python Not Found**
```
⚠️ Python not available - skipping advanced features
✅ Basic functionality available
```
→ Install Python 3.6+ for full feature set

**No Android Device**
```
❌ No Android device connected
```
→ Connect device or start emulator for instrumentation tests

**SDK Not Configured**
```
⚠️ Android SDK not found
```
→ Set ANDROID_HOME or install Android SDK

**Script Missing**
```
⚠️ Script not found - using basic functionality
```
→ Scripts are optional; basic Gradle tasks still work

### Performance Optimization

- **Fast Development**: Use `runUnitTestsOnlyAndGenerateSummary`
- **CI Builds**: Use standard mode without optional flags
- **Nightly/Full Validation**: Use combined mode with all flags
- **Memory Constrained**: Skip performance benchmarks

## Aviation Safety Compliance

The testing framework validates aviation safety requirements:
- GPS accuracy and performance monitoring
- Memory usage within safety limits (< 75%)
- UI responsiveness for critical operations
- Redux state management reliability
- Comprehensive error handling and recovery

All safety validations are automated and integrated into the testing pipeline for continuous compliance monitoring.