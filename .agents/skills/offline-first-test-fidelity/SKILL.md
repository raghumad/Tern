---
name: Offline-First Test Fidelity
description: Principles for accurately mapping UI tests to the app's offline caching design and preventing API rate limits. 
---

# Offline-First Test Fidelity

## Core Philosophy
The Tern Map engine is designed specifically for aviation, where physical disk storage (via Memory-Mapped FlexBuffers and Hilbert indexes) outlasts volatile internet connections. 

If instrumentation suites artificially destroy these spatial caches between every simulated user scenario, the metrics completely fail to measure standard real-world usage. Specifically, faking a volatile "fresh-slate" disk state violates performance testing parameters, guarantees eventual `Http 429 Too Many Requests` API blacklisting in Continuous Integration environments from high-bandwidth maps, and drastically inflates test suite execution durations.

## Essential Directives

### 1. Global Cache Persistence (`Warm Run`)
Do not invoke physical deletion queries (e.g. `CacheManager.clearAllCaches()`) inside Espresso `@After`, `@Before`, or typical layout teardown sequences.
- **Why**: Tearing down the cache forces the `UniversalCountryCacheManager` to continuously loop back out to the network for identically repeated global map configurations. The physical `.flex` caches map perfectly well between isolated Activity instances.

### 2. Cooperative Coroutine Cancellation
When explicitly tearing down a user session or Activity mock inside a test, any Coroutines that might currently be executing active download streams must be aborted gracefully.
- **Why**: Background `Jackson JsonParser` streams lock Java I/O tokens permanently. 
- **How**: Any raw bytecode-level loops must continuously cross-check `kotlinx.coroutines.isActive`.
  - Inside a stream: `while(!parser.isClosed && isActive)`
  - During test tear-down: `coroutineContext.cancelChildren()`

### 3. Deliberate Isolation over Annihilation
Rather than ripping the persistence layer out from underneath `OverlayCoordinator.kt`, simply execute a deliberate semantic decoupling event:
```kotlin
// Bad: Flushes the whole physical dataset preventing offline behavior verification.
CacheManager.clearAllCaches() 

// Good: Resets runtime animation memory while retaining the spatial dataset underneath.
OverlayCoordinator.reset()
```

When new overlays or classes are authored, mandate that they possess a robust internal array `reset()` override internally, rather than delegating state safety to an external cache wipe.
