# Overlay infrastructure audit (WS3)

Findings from reading the overlay system while building MezullaOverlayManager.
Each item is a backlog candidate, not an immediate fix.

## Patterns that are sound (followed as-is)

- **BaseOverlayManager lifecycle**: `onAttach` / `onDetach` / `onOverlayAttached` /
  `onOverlayDetached` is a clean template-method pattern. The redux subscription
  setup/teardown in the base class works correctly and prevents leaks.
- **OverlayCoordinator as central registry**: single coordinator owns FolderOverlay
  layers, universal pool, and animation manager. Managers register and get injected
  with the coordinator. This is the right topology.
- **Hilbert-curve spatial ordering for batch add/remove**: the visual effect of
  center-out addition is genuinely useful for paragliding map UX.
- **PeerReducer purity**: the reducer is a pure function, total over PeerAction,
  no side effects. Tests are clean and fast.

## Issues to fix

1. **OverlayType enum used as overlay config key, but not all types have configs.**
   `OverlayState` has fields for AIRSPACE, PG_SPOTS, ROUTES but not MEZULLA. The
   `getCurrentConfig()` in BaseOverlayManager has a `when` that would be non-exhaustive
   without the catch-all we added. Fix: either make OverlayState a `Map<OverlayType,
   OverlayConfig>` or add mezulla to the data class. The current per-field approach
   doesn't scale.

2. **MapStore dispatches three separate sealed types through the same `Any` queue.**
   MapAction, WeatherActions, and now PeerAction all go into `ConcurrentLinkedQueue<Any>`
   and are dispatched in `processBatchedActions` via `when (action)`. This works but the
   `else -> currentState` branch silently swallows unknown action types. Fix: make all
   actions implement a common `TernAction` marker interface, and make the `else` branch
   log a warning at minimum.

3. **WeatherActions is a separate sealed class, not a sealed interface.** PeerAction is
   a `sealed interface`, MapAction is a `sealed class`. The inconsistency is cosmetic but
   confusing for new readers. Fix: standardize on sealed interface for all three.

4. **`Middleware` interface processes `Any`, not a typed action.** The `Middleware.process`
   signature takes `(action: Any, store: MapStore)`. Every middleware must do its own
   type check. Fix: either type-parameterize the interface or accept a common action
   supertype.

5. **AirspaceOverlayManager directly adds polygons to `map.overlays` at index 0** instead
   of going through the coordinator's FolderOverlay. Every other manager uses a
   FolderOverlay layer. This special-casing means the z-ordering guarantee breaks when
   airspaces overlap with the mezulla layer. Fix: move airspaces into their own
   FolderOverlay like everyone else.

6. **PGSpotOverlayManager has a 1000+ line file** mixing weather orchestration, marker
   lifecycle, cache management, and rendering. The weather-fetching logic (viewport
   visibility checks, debouncing, batch dispatch) should be extracted into a separate
   class. Fix: split into PGSpotOverlayManager (marker lifecycle) and
   PGSpotWeatherOrchestrator (weather coordination).

7. **Emergency cleanup infrastructure is over-engineered for the current scale.** The
   `EmergencyCleanupResult`, `SpatialLattice`, zone-budget system, and memory-pressure
   callbacks add ~300 lines of code to BaseOverlayManager. There is no evidence any of
   this has been exercised in real flight or under real memory pressure. Fix: defer until
   actual OOM issues are observed; keep the API surface but simplify the implementation.

8. **OverlayCoordinator.addOverlayManager** has hardcoded `when` branches for each
   concrete manager type (AirspaceOverlayManager, PGSpotOverlayManager,
   RouteOverlayManager) to call `setCountryCacheManager`. This violates open/closed
   principle — every new manager requires a change to the coordinator. Fix: add an
   optional `CountryCacheAware` interface that managers implement if they need the
   country cache.

9. **`RankingTier` enum is defined but never used.** It was introduced as part of a
   "Rendering Safety Engine" concept but no code references it. Fix: delete it or defer
   until an actual feature needs screen-space priority ranking.

10. **`SpatialLattice` class is defined but never instantiated outside tests.** Same
    situation as RankingTier. Fix: delete or defer.

11. **`OverlayActions` sealed class in MapActions.kt** duplicates `MapAction.SetOverlayEnabled`
    and `MapAction.UpdateOverlayConfig`. It is never dispatched anywhere in the codebase.
    Fix: delete `OverlayActions`.

12. **Duplicate comment blocks in BaseOverlayManager** (`companion object` has the same
    comment twice: "Minimum zoom level to show overlays"). Minor, but symptomatic of
    copy-paste without review.

13. **PeerMiddleware doc says "lives alongside MapState, not inside it"** but WS3
    integration puts PeerState inside MapState. The doc in PeerState.kt and PeerMiddleware.kt
    should be updated to reflect the actual hosting arrangement.

14. **PerformanceDebugger coupling.** MapStore's `recordStateUpdate` catches exceptions
    from `PerformanceDebugger.recordStateUpdate`. The entire debug-recording system should
    be behind a compile-time flag or removed from the hot path. In release builds, this
    try/catch on every batch is wasted cycles.
