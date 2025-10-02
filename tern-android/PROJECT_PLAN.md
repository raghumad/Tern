# osmdroid + Compose Integration Fix Plan

## Context & Problem Statement
**Date:** October 1, 2025
**Status:** Planning Complete (68%) - Ready for Implementation

### Problem
The biggest pain point is making osmdroid work with Jetpack Composables - it's very buggy with:
- Lifecycle management issues
- Memory leaks
- State synchronization problems
- Permission handling complexity

### Requirements
- Jetpack Compose UI with custom overlays
- Offline maps (critical for paragliding)
- FlexBuffers + Hilbert indexing for spatial queries
- Sensor integration (accelerometer, GPS, etc.)
- 3D map navigation capabilities

## Analysis & Research

### Current Architecture Review
- MapView managed in ViewModel (good for config changes)
- AndroidView wrapper in Compose (causes lifecycle issues)
- FlexBuffers/Hilbert caching system (excellent, keep intact)
- Airspace and PG spots overlays (sophisticated implementation)

### Alternative Evaluation
**Mapsforge vs osmdroid:**
- Mapsforge: Same Compose integration challenges, less mature
- osmdroid: Better for our use case, proven in aviation apps
- **Decision:** Keep osmdroid, fix integration issues

## Solution Architecture

### Core Strategy
Optimize current osmdroid setup rather than migrate:
- Lower risk (1 week vs 2-3 weeks)
- Keep sophisticated caching system
- Fix known Compose integration bugs

### 4-Phase Implementation Plan

#### Phase 1: Redux Architecture & Core Lifecycle Fixes (2-3 days)
- [x] Fix MapView lifecycle with DisposableEffect
- [x] Improve permission handling with rememberPermissionState
- [x] Replace inefficient produceState with StateFlow
- [x] Add proper cleanup to prevent memory leaks
- [x] Set up Redux Store for global map state (rotation, overlays, location)
- [x] Migrate ViewModel state to Redux actions/reducers
- [x] Update composables to observe Redux store instead of ViewModel
- [x] Implement reusable Redux middleware for Combine flows

**Implementation Details:**
- Added `mapRotation` StateFlow to MapViewModel with updates in map listeners
- Replaced produceState with collectAsState() in MapViewContainer
- Migrated to rememberPermissionState from Accompanist for cleaner permission handling
- Added DisposableEffect in composable (ViewModel already had onCleared cleanup)
- Added accompanist-permissions:0.32.0 dependency

#### Phase 2: Enhanced Overlay Architecture
##### Chunk 1: Overlay State Foundation
- [x] Add OverlayState and OverlayConfig to Redux state
- [x] Add overlay-related actions (SetOverlayEnabled, UpdateOverlayConfig)
- [x] Update reducers to handle overlay state
- [x] Verify Redux state updates work correctly

##### Chunk 2: Overlay Manager Interface ✅
- [x] Create OverlayManager interface with lifecycle methods
- [x] Define OverlayType enum (AIRSPACE, PG_SPOTS, SENSORS, TERRAIN)
- [x] Create BaseOverlayManager with common functionality
- [x] Test interface compiles and can be implemented
- [x] Created overlays package with:
  - OverlayManager.kt - Interface defining overlay lifecycle methods
  - BaseOverlayManager.kt - Abstract base class with Redux integration, debouncing, and common functionality
  - StubOverlayManager.kt - Implementation for testing compilation

##### Chunk 3: AirspaceOverlayManager MVP
- [ ] Create AirspaceOverlayManager composable
- [ ] Extract airspace logic from MapViewModel to manager
- [ ] Connect to Redux for visibility toggle
- [ ] Test airspace overlays work with Redux toggle

##### Chunk 4: PGSpotOverlayManager
- [ ] Create PGSpotOverlayManager composable
- [ ] Extract PG spot logic from MapViewModel
- [ ] Connect to Redux state
- [ ] Test both overlay types work independently

##### Chunk 5: Overlay Coordinator Integration
- [ ] Create OverlayCoordinator composable to manage all overlays
- [ ] Update MapViewContainer to use coordinator instead of direct ViewModel calls
- [ ] Remove overlay logic from MapViewModel
- [ ] Test all overlays work through Redux state

##### Chunk 6: Performance Optimization
- [ ] Add viewport-based lazy loading
- [ ] Implement overlay batching for map updates
- [ ] Add memory management for off-screen overlays
- [ ] Test performance with multiple overlay types enabled

#### Phase 3: Advanced Features (3-4 days)
- [ ] Sensor integration (accelerometer, compass, GPS)
- [ ] 3D terrain visualization capabilities
- [ ] Enhanced location accuracy with sensor fusion
- [ ] Aviation-specific features (wind vectors, altitude data)

#### Phase 4: Testing & Polish (1-2 days)
- [ ] Comprehensive testing across devices
- [ ] Performance profiling and optimization
- [ ] Bug fixes and edge case handling
- [ ] Documentation updates

## Key Technical Decisions

### Architecture Choices
- **Keep osmdroid**: Better fit than Mapsforge for offline aviation use
- **Preserve caching**: FlexBuffers + Hilbert indexing is superior
- **Compose-first**: All new UI components use Jetpack Compose
- **Sensor fusion**: Combine GPS, accelerometer, compass for accuracy
- **Redux architecture**: Global store for predictable state management, enabling complex overlay interactions and sensor fusion from Phase 1

### Implementation Approach
- **Incremental**: Each phase testable independently
- **Backward compatible**: No breaking changes to existing features
- **Performance focused**: Memory management and smooth rendering
- **Aviation optimized**: Features for paragliding navigation

## Risk Assessment
- **Low Risk**: Building on solid foundation
- **Known Issues**: Clear fixes for identified problems
- **Fallback Plan**: Can revert changes if needed
- **Testing Strategy**: Incremental validation at each phase

## Progress Tracking
- **Built-in System**: Automatic progress updates in conversations
- **GitHub Projects**: Optional manual project management
- **File-based**: This document tracks long-term context

## Next Steps
1. Get final approval for approach
2. Switch to Act mode for implementation
3. Begin Phase 1: Core lifecycle fixes
4. Update this file with progress after each phase
