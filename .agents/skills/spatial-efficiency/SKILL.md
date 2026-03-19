---
name: Spatial Efficiency
description: Guidelines for high-performance spatial data management using Hilbert spatial indexing and memory-mapped buffers to prevent OOM errors.
---

# Spatial Efficiency Skill

This skill documents the "Aviation-Grade" performance pattern used in Tern to handle large-scale spatial datasets (Airspaces, PG Spots, Weather) on mobile devices with limited heap memory.

## Core Principles

### 1. Avoid Heap-Resident Datasets
Never load entire regional or country-level GeoJSON datasets into the JVM heap. For files larger than a few hundred KB, use memory-mapped I/O (`MappedByteBuffer`) to keep data in safe, native memory regions that don't trigger GC pressure.

### 2. Spatial Locality via Hilbert Indexing
When querying for "nearby" features, use **Hilbert Curve Spatial Indexing**. This converts multi-dimensional spatial data into a single-dimension index that preserves locality, allowing for efficient range queries without scanning the entire dataset.

### 3. Zero-Copy Deserialization
Prefer binary formats like **FlexBuffers** or FlatBuffers that allow accessing specific fields without deserializing the entire object. This is critical for high-density map rendering where thousands of features might be processed per frame.

### 4. Bounded Object Pooling
Any UI elements generated from spatial data (Markers, Polygons, Paths) must be managed via a bounded pool (like `UniversalOverlayPool`). Reuse existing objects instead of allocating new ones during map movement to eliminate memory churn.
### 5. Dynamic Spatial Boundaries
Never hardcode geographic, political, or spatial boundaries (e.g., country adjacency maps). All spatial resolutions MUST use dynamic geocoding (e.g., `CountryUtils.getNearbyCountryCodes`) or mathematical spatial indexing. This guarantees global portability and prevents the system from being restricted to hand-coded regions.

### 6. Memory-Efficient View-to-Bitmap Caching
For dynamic overlays (e.g., Wind Gauges) generated from UI views, use a sufficiently sized `LruCache` to prevent "Cache Thrashing" and excessive `ComposeView` allocations during rapid panning in high-density regions.

### 7. Centralized Adaptive Budgeting (SSOT)
All resource-intensive caches (bitmaps, object pools, active feature sets) MUST be dynamically resized based on the centralized `AdaptiveOverlaySystem` budget provided by the `OverlayCoordinator`. Never hardcode cache sizes for map overlays; instead, subscribe to `onOverlayBudgetChanged` and call `LruCache.resize()` to ensure the system's memory-aware "Source of Truth" is consistently applied.

### 8. Lazy Budget-Aware Hydration
Always apply rendering budgets *before* object hydration. In spatial queries, "peek" at feature centroids in the memory-mapped buffer to calculate distances and sort/thin features *prior* to full `OverlayFeature` allocation. This prevents JVM heap spikes during wide-area (regional) map navigation by keeping non-visible data in the native buffer.

### 9. Zoom-Aware Spatial Scaling
Scale data query radii and feature density budgets based on the map's current zoom level. A fixed search radius (e.g., 200km) is insufficient for regional situational awareness (Zoom 5) and excessively "heavy" for local views (Zoom 15). Use adaptive spatial scaling (e.g., 20km at Z15 to 1000km at Z5) to ensure the screen is fully populated without OOM risk.

## When to Apply

- **Feature Scanning**: Any task that involves "scanning" or "querying" local feature caches.
- **New Overlay Types**: When adding a new map layer (e.g., Obstacles, Terrain, Radar).
- **OOM Troubleshooting**: If an instrumentation test fails with `OutOfMemoryError` during rapid panning or zooming.
- **Data Synchronization**: When designing how country-level data is downloaded and stored locally.

## Implementation Pattern

1. **Storage**: Delegate storage to `SpatialDiskCache`.
2. **Querying**: Use `diskCache.queryNearby(countryCode, center, distanceMiles)`.
3. **Serialization**: Use `MapOverlayCacheUtils` for Hilbert index generation and FlexBuffers serialization.
4. **Coordination**: Ensure the manager is connected to `UniversalCountryCacheManager` for proactive data loading.

## Application Checklist
- [ ] Is the data being read directly from a memory-mapped buffer instead of a full heap list?
- [ ] Is the query using a Hilbert index to narrow the search space?
- [ ] Are we avoiding `JSON` parsing in the hot path (map movement)?
- [ ] **Adaptive Budgeting**: Is the resource (bitmap cache, object pool) dynamically resized based on `OverlayBudget`? (No hardcoded limits).
- [ ] **Spatial SSOT**: Is the query key specific enough (e.g., location + radius) to prevent stale results from different areas of the same country?
- [ ] **Lazy Hydration**: Is the budget (limit) applied *before* object creation in the disk cache?
- [ ] **Zoom-Aware Scaling**: Does the query radius adjust dynamically based on the current zoom level?
- [ ] **Regional Context**: Does the budgeting logic preserve minimal context for regional views (Zoom < 8) even under memory pressure? (No "Halo Effect").
- [ ] Does the test suite include a "Regional Zoom-Out" stress test for this data?
