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

### Python Scripts (Conditional Execution)
All Python scripts are executed conditionally based on Gradle properties and gracefully handle missing dependencies:

- `check_coverage_threshold.py`: Coverage verification with quality gates
- `coverage_dashboard.py`: Interactive HTML dashboard with analytics
- `coverage_trend_analysis.py`: Historical trend analysis and regression detection
- `generate_coverage_badge.py`: Shields.io badge generation for CI/CD
- `run_performance_benchmarks.py`: Aviation safety performance validation
- `android_test_automation.py`: Zero-step automated testing pipeline

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