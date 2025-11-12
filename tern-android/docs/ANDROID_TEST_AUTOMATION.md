# Local Android Instrumentation Test Automation

This document describes the local automated framework for running Android instrumentation tests with comprehensive quality reporting.

## 🚀 Quick Start

### One-Command Execution

Run all tests with coverage and quality reporting:

```bash
./gradlew testWithCoverage
```

**Output:**
- ✅ JaCoCo coverage reports (HTML/XML)
- ✅ Comprehensive test summary (`test-summary.md`)
- ✅ Quality metrics and recommendations
- ✅ Aviation safety validation

### Manual Testing (Without Automation)

For manual emulator testing:

```bash
# Start emulator manually
emulator -avd your_avd_name -no-window -gpu swiftshader_indirect

# Run tests
./gradlew connectedDebugAndroidTest
```

## 🏗️ Framework Architecture

### Current Implementation

1. **JaCoCo Integration**: Combined unit + instrumentation test coverage
2. **Combined Test Task**: `testWithCoverage` generates coverage + summary reports
3. **Cache Layer Tests**: Android instrumentation tests for RouteCache validation
4. **Quality Analytics**: Dynamic scoring and regression analysis

### Planned Automation (Local Python Framework)

- **SDK Management**: Automatic Android SDK download and setup
- **Emulator Creation**: Programmatic AVD creation with Pixel Pro profile
- **Test Execution**: Automated emulator launch, test run, and cleanup
- **Configuration**: JSON-based settings for device and API level selection

## 📊 Test Results & Quality Reporting

### Automatic Quality Dashboard

The `testWithCoverage` task generates:

- **JaCoCo Coverage Reports**: HTML/XML coverage analysis
- **Test Summary Report** (`test-summary.md`):
  - Test execution results (pass/fail counts)
  - Code coverage metrics with status indicators
  - Quality score (10-point scale)
  - Aviation safety compliance validation
  - Actionable improvement recommendations

### Coverage Analysis

- **Unit Tests**: Business logic, utilities, Redux state (~40-50% coverage)
- **Instrumentation Tests**: UI integration, Android framework (~20-30% coverage)
- **Combined**: Complete application coverage (~60-80% total)

## 🧪 Current Test Coverage

### Implemented Tests ✅
- **FlexBuffers Serialization**: Route data persistence validation
- **Empty Route Handling**: Edge case validation
- **Single Waypoint Routes**: Minimal route scenarios
- **Cache Statistics**: Metadata validation

### Planned Test Expansions 🔮
- **Overlay Manager Tests**: Redux integration, memory management
- **UI Regression Tests**: Compose rendering, gesture handling
- **GPS Safety Tests**: Aviation GPS validation
- **Memory Monitoring Tests**: Heap usage validation
- **Performance Benchmarks**: Dispatch frequency monitoring

## ⚙️ Configuration

### Test Task Configuration

The `testWithCoverage` task is configured in `build.gradle.kts`:

```kotlin
tasks.register<JacocoReport>("testWithCoverage") {
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest", "generateTestSummary")
    // Generates combined coverage + quality summary
}
```

### Quality Metrics

Dynamic scoring based on:
- Test execution completeness
- Code coverage levels
- Safety validation coverage
- Integration test presence
- Automation maturity

## 🔧 Local Development Workflow

### Standard Development Cycle

1. **Write Code**: Implement features with tests
2. **Run Tests**: `./gradlew testWithCoverage`
3. **Review Quality**: Check `test-summary.md` for metrics
4. **Address Issues**: Fix based on recommendations
5. **Validate**: Re-run tests to confirm improvements

### Quality-Guided Development

The test summary serves as your **quality compass**:
- 🟢 **Green**: High quality, proceed with confidence
- 🟡 **Yellow**: Good but has improvement opportunities
- 🔴 **Red**: Quality issues need immediate attention

## 📈 Performance & Metrics

### Test Execution Times

- **Unit Tests**: ~30-60 seconds
- **Instrumentation Tests**: ~3-5 minutes (with emulator)
- **Combined Task**: ~4-6 minutes total
- **Report Generation**: ~10-20 seconds

### Quality Score Components

| Component | Weight | Description |
|-----------|--------|-------------|
| Test Execution | 20% | Test completeness and success rates |
| Code Coverage | 20% | JaCoCo coverage percentages |
| Safety Validation | 30% | Aviation safety compliance |
| Integration Testing | 15% | Component interaction validation |
| Automation | 15% | Test infrastructure maturity |

## 🚨 Troubleshooting

### Common Issues

**Emulator Connection Failed**
- Ensure emulator is running: `adb devices`
- Check emulator options: `-no-window -gpu swiftshader_indirect`
- Verify ADB connection: `adb kill-server && adb start-server`

**Coverage Reports Empty**
- Check JaCoCo configuration in `build.gradle.kts`
- Ensure `enableAndroidTestCoverage = true`
- Verify test execution completed successfully

**Test Summary Not Generated**
- Check `generateTestSummary` task dependencies
- Verify file permissions for `build/reports/` directory
- Check for Gradle execution errors

### Debug Mode

Enable verbose logging:

```bash
./gradlew testWithCoverage --info
```

## 📚 Related Documentation

- [Test Quality Improvement Plan](TEST_QUALITY_IMPROVEMENT_PLAN.md)
- [Redux API Documentation](REDUX_API.md)
- [Route Planner Architecture](route_planner.md)

## 🎯 Future Enhancements

- **Local Python Automation**: Automated SDK/emulator management
- **Multi-Device Testing**: Test across different Android devices
- **Performance Baselines**: Track execution times and fail on regressions
- **Screenshot Testing**: Visual regression testing for UI components
- **Advanced Analytics**: Trend analysis and predictive quality metrics
