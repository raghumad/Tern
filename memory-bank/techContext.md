# Tech Context

## Technology Stack

### iOS Development (Tern/)
- **Language**: Swift 5.x+
- **UI Framework**: SwiftUI with MapKit integration
- **Build System**: Xcode with standard project configuration
- **Platform APIs**: MapKit, CoreLocation, URLSession
- **Entitlements**: Location services, network access
- **Assets**: Custom icon set and branded fonts (Gruppo-Regular.ttf)

### Android Development (tern-android/)
- **Language**: Kotlin 1.8.x+
- **UI Framework**: Jetpack Compose with osmdroid maps
- **Build System**: Gradle with Kotlin DSL (build.gradle.kts)
- **Maps**: osmdroid (open source OSM offline maps)
- **Architecture**: Redux-first state management (custom implementation)
- **Caching**: FlexBuffers for zero-copy serialization and Hilbert spatial indexing
- **Performance Targets**: <10 Redux dispatches/sec, <75% memory usage, 60fps UI
- **Testing**: JUnit, Espresso, Mockito with JaCoCo coverage
- **Dependencies**: Compose BOM, Lifecycle, Coroutines, osmdroid, FlexBuffers

### Cross-Platform Shared Components
- **Models**: Weather forecasts, airspaces, hotspots, waypoints
- **Data Formats**: JSON for API responses
- **API Integrations**: NWS Weather, OpenMeteo forecasts
- **Caching**: FlatBuffers for efficient serialization
- **Spatial Algorithms**: Hilbert indexing for geographic queries

## Development Environment

### Setup Requirements
- **macOS/iOS**: Xcode 14+ for iOS development
- **Android**: JDK 11+, Android SDK (auto-download via scripts)
- **Git**: Version control with remote repository
- **IDE**: VS Code with platform extensions

### Build and Test Commands
- **iOS**: `xcodebuild` (via Xcode)
- **Android Manual**: `./gradlew assembleDebug`
- **Android Automated**: `./gradlew runAutomatedTests`
- **Coverage**: `./gradlew testWithCoverage`

## Dependencies and Libraries

### Android Dependencies (key ones)
- `androidx.compose:*` - UI framework
- `com.google.android.gms:play-services-maps` - Mapping
- `org.jetbrains.kotlinx:kotlinx-coroutines` - Async operations
- `com.google.flatbuffers:flatbuffers-java` - Serialization
- `androidx.test:*` - Testing framework

### iOS Frameworks
- SwiftUI (built-in)
- MapKit (built-in)
- Combine (built-in)
- Foundation/CFNetwork (built-in)

### External APIs
- **NWS Weather**: US National Weather Service API
- **OpenMeteo**: European weather forecasts
- **Airspace Data**: Aviation databases (implementation needed)

## Testing and Quality Assurance

### Android Testing Strategy
- **Unit Tests**: Kotlin/JUnit for business logic
- **Instrumentation Tests**: Device-specific behavior
- **UI Tests**: Espresso for gesture testing
- **Coverage**: JaCoCo integration with thresholds
- **Automation**: Python scripts for full test execution

### iOS Testing (To Be Assessed)
- XCUnit framework capabilities
- UI testing integration
- Coverage tools integration needed

## Deployment and Distribution

### Android
- APK generation via Gradle
- Automated testing pipeline
- Store distribution via Google Play

### iOS
- IPA generation via Xcode
- TestFlight for beta distribution
- App Store distribution

### Aviation Standards Compliance
- Safety-critical software practices
- Memory usage monitoring
- Performance benchmarks
- GPS accuracy validation

## Tools and Scripts

### Automation Tools
- `scripts/android_test_automation.py` - Complete test pipeline
- Configuration files: `android-test-config.json`
- Gradle custom tasks for testing and coverage

### Documentation
- `docs/` directory for technical documentation
- `docs/REDUX_API.md` - Android API specification
- `AGENTS.md` - Project status and guidelines
- Memory bank system for context preservation
