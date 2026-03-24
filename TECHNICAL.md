# Technical Specifications & Architecture

This document outlines the technical foundation of the Tern flight deck for developers and contributors.

## Core Architecture

Tern is built with a focus on **Safety-First** principles, ensuring high reliability and low-latency performance in critical flight environments.

### Unidirectional State Management
The app utilizes a Redux-inspired pattern for state management.
- **Action Batching**: High-frequency sensor and GPS updates are batched in 100ms windows to prevent UI jank and maintain a consistent 60fps rendering rate.
- **MapStore**: A specialized store for managing spatial state and overlay budgets.

### High-Performance Data Layer
To handle large-scale spatial data (Airspaces, Weather, PG Spots) without memory overhead:
- **Zero-Copy Serialization**: Uses **FlatBuffers/FlexBuffers** to eliminate parsing at runtime.
- **Hilbert Spatial Indexing**: Maps 2D geographical coordinates to 1D integers, enabling O(log N) proximity searches.
- **Two-Level Caching**: LRU memory cache (L1) and persistent spatial disk cache (L2).

### Adaptive Overlay System (AOS)
The AOS manages hundreds of map markers dynamically based on:
- **Zone Budgeting**: Categories like CORE, NEAR, and FAR limit the number of active renders.
- **Memory Pressure Awareness**: Triggers emergency cleanup when Android signals low memory.
- **Flight Phase Logic**: Adjusts rendering priority based on whether the pilot is launching, cruising, or landing.

## Development & Testing

### Aviation-Grade Definition of Done (DoD)
All features must meet the following criteria:
1. **Instrumented Stability**: Passing 100% of instrumented tests on target hardware.
2. **Offline Fidelity**: Verified functionality with zero network connectivity.
3. **UX Glanceability**: Critical metrics readable within 0.5 seconds in high-glare simulations.

### Build Instructions
```bash
# Clone the repository
git clone https://github.com/raghumad/Tern.git

# Build the Android app
cd tern-android
./gradlew assembleDebug
```

For more detailed guides, see the [docs/guides](file:///home/raghu/src/Tern/docs/guides) directory.
