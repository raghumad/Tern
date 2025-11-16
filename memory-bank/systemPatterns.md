# System Patterns

## Core Architecture Patterns

### Cross-Platform Development
- Separate iOS (SwiftUI) and Android (Compose/Kotlin) implementations
- Shared data models and business logic concepts
- Independent UI frameworks but consistent user experience
- Platform-specific optimizations while maintaining feature parity

### State Management
- **Android**: Redux-first architecture with Kotlin data classes
  - Single Source of Truth: Redux Store manages all application state
  - Action → Reducer → State Flow: Predictable state updates through sealed actions
  - Component Observation: UI components observe state with `.collectAsState()`
  - Performance Batching: <10 Redux dispatches/sec target (mandatory)
  - Bridge Pattern: ReduxMapBridge for clean separation of Redux integration
  - Reactive Callbacks: Late-binding initialization compatible with ViewModel
- **iOS**: SwiftUI state management (to be analyzed)
  - Observable objects and published properties
  - Environment objects for shared state

### Data Persistence
- **Caching System**: TernCache pattern across platforms
  - FlatBuffers for zero-copy serialization
  - Memory-mapped I/O for performance
  - Versioned data formats for backward compatibility
- **Spatial Indexing**: Hilbert curve algorithm
  - 16-bit precision for efficient queries
  - Location-based filtering for routes and hotspots
  - Aviation-grade spatial accuracy

### Component Architecture
- **Route Ownership**: Routes own waypoints in hierarchical structure
- **Overlay Management System**: BaseOverlayManager pattern with distance-based zoning and memory-adaptive allocation
- **Hilbert Curve Ordering**: Spatial animations that load overlays center→outside smoothly
- **Annotation System**: Custom map annotations with callouts
- **Weather Integration**: Multiple sources (NWS, OpenMeteo) with fallbacks

### Overlay Architecture (Android)
- **Unified Caching**: AirspaceCache and PGSpotCache with multi-layer validation
  - Source file validation (JSON structure, file size)
  - Feature validation (coordinate bounds, geometry)
  - Cache integrity validation (corruption detection)
- **Race Condition Prevention**: Atomic download flags prevent duplicate cache operations
- **BaseOverlayManager Pattern**: All overlays extend this class for Redux integration
- **Distance-Based Zoning**: 5-tier spatial hierarchy (CORE→NEAR→MID→FAR→EXTREME)
- **Memory-Adaptive Budgets**: Dynamic overlay limits based on device memory class (50-400 overlays)
- **Hilbert Curve Ordering**: Center→outside overlay animations for smooth loading
- **Aviation Priority**: Safety-critical overlays never reduced under memory pressure

## Safety and Reliability Patterns

### Aviation Safety Standards
- **GPS Validation Before Operations**: All aviation operations require validated GPS fix
- **Progressive Enhancement**: App works at all sensor capability levels (Aviation→Flight→Mobile→Basic grade)
- **Smooth Visual Transitions**: Zero jarring overlay changes during flight
- **Visual Continuity**: Predictable behavior during border crossings and airspace changes
- **Memory usage constraints (<75% heap utilization)
- **Error Recovery**: Graceful degradation without compromising safety

### Memory Management
- Adaptive memory allocation
- Reference counting and lifecycle management
- Weak references for delegates and observers
- Background cleanup operations

### Error Handling
- Standardized exception handling across layers
- Graceful degradation for connectivity issues
- Comprehensive logging and diagnostics
- Recovery mechanisms for critical operations

## Development Patterns

### Hybrid Development Pattern (Android)
- **Phase 1**: UX-first with ViewModel-based state management for rapid prototyping
- **Phase 2**: Redux bridge creation - add Redux actions that sync with simple store
- **Phase 3**: Feature enhancement under Redux while maintaining simple store backup
- **Phase 4**: Gradual transfer of primary responsibility to Redux incrementally

### Redux Migration Strategy
- **Migration Criteria**: Only start when core functionality works and UX is validated
- **Incremental Approach**: Avoid big-bang migrations - convert gradually to Redux
- **Testing During Migration**: State immutability, action processing, bridge sync validation
- **Anti-Patterns**: No Redux-first architecture, no parallel development of simple+Redux

### Implementation Principles
- **UX-First Development**: Get user experience working before architectural complexity
- **Incremental Progress**: Small, testable changes with immediate validation
- **Working System First**: Implement basic functionality with simple state management
- **Code Quality First**: Refactoring and testing before new features

## Development and Testing Patterns

### Automated Testing
- **Android**: Multi-layer testing strategy
  - Unit tests for business logic (fast, development feedback)
  - Instrumentation tests for device features
  - UI tests for gesture and visual interactions
  - Automated execution with comprehensive coverage reporting
- **Code Coverage Requirements**:
  - Unit test coverage: Target 70%+ for core business logic
  - Integration test coverage: Safety-critical paths fully covered
  - Regression protection: Prevent manual testing after changes
- **Quality Score**: 10-point scale (current target 8.5+)
- Consistent naming conventions
- Documentation coverage requirements
- File Modification: Use `diff` and `patch` commands for precise editing

### Spatial and Geographic Patterns
- WGS84 coordinate system
- Aviation-specific distance calculations
- Crossing detection for airspace boundaries
- Altitude and elevation handling
- GeoJSON conversion: Always convert [longitude, latitude] to GeoPoint(latitude, longitude)
- Distance calculations: Miles for UI, meters for OSMDroid calculations

### Critical Bugfixes (Android)
- **Performance**: Move ALL processing to `Dispatchers.IO` to prevent ANR crashes
- **Architecture**: Delay map operations until GPS fix to prevent invalid API calls
- **Cache Management**: Never auto-clear caches on startup (preserves offline capability)
- **Spatial Queries**: Always use `queryNearbyFeatures()` only (never entire countries)
