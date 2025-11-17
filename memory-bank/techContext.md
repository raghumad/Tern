# Tech Context

## Technology Stack

### Android Development (PRIMARY PLATFORM - tern-android/)
- **Language**: Kotlin 1.8.x+
- **UI Framework**: Jetpack Compose with osmdroid maps
- **Build System**: Gradle with Kotlin DSL (build.gradle.kts)
- **Maps**: osmdroid (open source OSM offline maps)
- **Architecture**: Redux-first state management (custom implementation)
- **Caching**: FlexBuffers for zero-copy serialization and Hilbert spatial indexing
- **Performance Targets**: <10 Redux dispatches/sec, <75% memory usage, 60fps UI
- **Testing**: JUnit, Espresso, Mockito with JaCoCo coverage
- **Dependencies**: Compose BOM, Lifecycle, Coroutines, osmdroid, FlexBuffers

### iOS Development (ARCHIVED - Tern/)
- **Status**: No active development - substantial feature gaps identified in parity assessment
- **Gaps vs Android MVP**: Missing Redux state management, FlatBuffers caching, overlay system, interactive editing
- **Recommendation**: Avoid development to prevent resource waste on duplicative effort

### Cross-Platform Shared Components
- **Models**: Weather forecasts, airspaces, hotspots, waypoints
- **Data Formats**: JSON for API responses
- **API Integrations**: NWS Weather, OpenMeteo forecasts
- **Caching**: FlatBuffers for efficient serialization
- **Spatial Algorithms**: Hilbert indexing for geographic queries

## Development Environment

### Setup Requirements
- **Android**: JDK 11+, Android SDK (auto-download via scripts) - PRIMARY DEVELOPMENT ENVIRONMENT
- **macOS/iOS**: Xcode 14+ available but NO ACTIVE iOS DEVELOPMENT
- **Git**: Version control with remote repository
- **IDE**: VS Code with platform extensions

### Build and Test Commands
- **Android Manual**: `./gradlew assembleDebug`
- **Android Automated**: `./gradlew runAutomatedTests` (PRIMARY DEVELOPMENT WORKFLOW)
- **Coverage**: `./gradlew testWithCoverage`
- **iOS**: No active build commands (development halted)

## Dependencies and Libraries

### Android Dependencies (key ones)
- `androidx.compose:*` - UI framework
- `com.google.android.gms:play-services-maps` - Mapping
- `org.jetbrains.kotlinx:kotlinx-coroutines` - Async operations
- `com.google.flatbuffers:flatbuffers-java` - Serialization
- `androidx.test:*` - Testing framework


### External APIs
- **NWS Weather**: US National Weather Service API
- **OpenMeteo**: European weather forecasts
- **Airspace Data**: Aviation databases (implementation needed)

## UI/UX Design Principles

### Architecture Decisions (Android-Focused)
All architecture decisions (Redux, FlatBuffers, overlay management, testing) are designed and validated for Android implementation. The Android-first approach ensures optimal performance, user experience, and maintainability on the primary platform.

### Intuitive Interaction Patterns (Android)
- **Contextual Gesture Behavior**: Interactions adapt based on proximity and state
  - Long press near existing waypoint = select, not create
  - Tap on selected item = deselect, tap elsewhere = clear selection
  - Future: Multi-touch gestures for advanced operations
- **Progressive Disclosure**: Complex features revealed contextually
- **Consistent Feedback**: Visual/audio feedback for all interactions
- **Error Prevention**: Smart validation prevents user mistakes
- **Accessibility First**: Screen reader support, high contrast, gesture alternatives

### Testing and Quality Assurance

### Android Testing Strategy
- **Unit Tests**: Kotlin/JUnit for business logic
- **Instrumentation Tests**: Device-specific behavior
- **UI Tests**: Espresso for gesture testing
- **Coverage**: JaCoCo integration with thresholds
- **Automation**: Python scripts for full test execution

### iOS Testing (ARCHIVED)
- No active testing framework (development halted)
- Parity assessment revealed substantial gaps vs Android MVP testing capabilities

## Deployment and Distribution

### Android
- APK generation via Gradle
- Automated testing pipeline
- Store distribution via Google Play

### iOS (ARCHIVED)
- No active deployment pipeline (development halted)
- Parity assessment identified substantial feature gaps requiring duplicative development effort

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
