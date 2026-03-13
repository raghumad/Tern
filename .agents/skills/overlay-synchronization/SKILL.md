---
name: Overlay Synchronization & Diagnostics
description: Principles and patterns for robust map overlay parsing, reactive synchronization, and deep instrumentation.
---

# Skill: Overlay Synchronization & Diagnostics

Patterns and diagnostic strategies for ensuring high-performance map overlays are correctly parsed, synchronized with asynchronous data sources, and verified through deep instrumentation.

## Core Principles

### 1. Robust Format Detection
When dealing with mixed data streams (Standard GeoJSON vs. NDGeoJSON), never rely on simple presence checks (e.g., just checking for `{`).
- **Standard GeoJSON**: Must start with `{ "type": "FeatureCollection"` or `{ "type": "Feature"`.
- **NDGeoJSON**: Detected by looking for `}\n{` or multiple lines where each is a JSON object.
- **Minification Safety**: Ensure regex or string checks account for both minified and pretty-printed variants.

### 2. Reactive Synchronization (Spatial + Async)
Overlays should never rely on a single trigger. Implement a "Dual-Trigger" pattern:
- **Spatial Trigger**: Re-evaluate data visibility on significant map movement (use distance-based throttling to prevent UI churn).
- **Asynchronous Trigger**: Register for country/regional data events. If data is loaded in the background (cache preloads), the overlay manager must immediately check and load available features for the current viewport.

### 3. Deep Verification (Recursive Discovery)
Automated UI tests for map-heavy applications often fail when overlays are grouped for performance (e.g., using `FolderOverlay`).
- **Recursive Counting**: Instrumentation helpers (like `waitForMapData`) must traverse nested overlay groups recursively.
- **Avoid Shallow Filters**: Never use `mapView.overlays.filterIsInstance<Marker>()` as it misses sub-layers.

### 4. Visibility State Management
Standardize `isEnabled()` logic at the base class level to ensure consistent Redux state propagation.
- **Redux Integration**: Always link visibility to centralized state to prevent "Ghost Overlays" that are active in logic but hidden in UI.

### 5. Test State Isolation & Determinism
Automated UI tests that verify spatial data or async caching are highly susceptible to state leakage between runs.
- **Cache Manager Isolation**: High-level managers (e.g., `UniversalCountryCacheManager`) must completely wipe in-memory collections (like `cachedCountries`) in their `reset()` method. Relying solely on `CacheManager.clearAllCaches()` for disk clears is insufficient if RAM state persists.
- **Geographic Debounce Reset**: Component managers (e.g., `AirspaceOverlayManager`, `PGSpotOverlayManager`) use variables like `lastCheckLocation` or `lastLoadPosition` to throttle queries based on map movement. These *must* be cleared during `reset()`. If a new test starts near where the previous test ended, the system might skip loading data because the distance delta is too small.

## Diagnostic Checklist
If an overlay isn't appearing:
1. **Parser Check**: Did `isNdGeoJson` return the correct value? Check logs for "parsed 0 features".
2. **Trigger Check**: Was `checkAndLoad` called? Verify `lastLoadPosition` and map move delta.
3. **Registration Check**: Is the manager listening to `onCountryLoaded`?
4. **Z-Order Check**: Is it being added to index 0 (bottom) or the top layer?
5. **Test Determinism**: If the failure is only in batch test runs, check if `lastCheckLocation` or `cachedCountries` are persisting from a previous test.
