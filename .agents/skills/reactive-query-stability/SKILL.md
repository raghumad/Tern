---
description: Patterns for managing concurrent spatial queries in reactive UIs to prevent performance degradation and race conditions.
---

# Reactive Query Stability

This skill defines strategies for managing concurrent spatial queries in a reactive environment to prevent performance degradation and race conditions.

## 1. Query Deduplication (In-Flight Coalescing)

When multiple consumers (e.g., Overlay Managers) require data for the same spatial region, avoid redundant disk I/O and deserialization by coalescing concurrent requests.

### Pattern: `Deferred` Coalescing
Use a thread-safe map to track active jobs by a unique key (e.g., Country Code or bucketed coordinates).

```kotlin
private val activeQueries = ConcurrentHashMap<String, Deferred<List<Feature>>>()

suspend fun query(key: String): List<Feature> = coroutineScope {
    activeQueries.getOrPut(key) {
        async {
            // Perform actual expensive work here
            performExpensiveSpatialQuery(key)
        }
    }.await()
}
```

## 2. Lazy Deserialization (Zero-Copy Persistence)

Binary formats like FlexBuffers allow for partial data access without full object hydration. Full deserialization into JVM `Map` or `POJO` lists is a primary source of GC pressure.

### Pattern: Reference-Based Access
Instead of `List<FeatureObject>`, return `List<FeatureReference>` which keeps a pointer to the memory-mapped buffer and only hydrates properties on demand.

- **Bad**: Converting 10,000 features into `Map<String, Any>` for a distance check.
- **Good**: Reading the `centroid` directly from the buffer for filtering; only hydrating UI properties when the feature is selected.

## 3. Trigger Stability

Reactive UI triggers (Redux changes, Location updates, Event listeners) often fire in "storms." Sanitize these triggers to prevent redundant loading jobs.

- **Unified Entry Point**: All triggers should route through a single, synchronized `checkAndLoad()` method that handles job cancellation and debouncing.
- **State Guarding**: Verify that the environment is ready (e.g., Map View initialized, permissions granted) before launching heavy loads.
- **Distance Throttling**: Ignore location updates until a significant movement threshold (e.g., 500m) is crossed.

## 4. Resource Budgeting

When loading overlays, use a calculated budget based on current memory pressure and location criticality.

- **Core Zones**: Higher density allowed near the pilot/center.
- **Far Zones**: Aggressively thin out or remove overlays to preserve heap.
- **Adaptive Unloading**: Explicitly call `System.gc()` or clear caches if the `PerformanceDebugger` detects a retained delta spike.
