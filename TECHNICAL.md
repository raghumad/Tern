# Technical Overview

## State Management

Redux-inspired unidirectional data flow.
- **Action Batching**: High-frequency sensor and GPS updates are batched in
  100ms windows to avoid UI jank.
- **MapStore**: Specialized store for spatial state.

## Data Layer

Spatial data (airspaces, weather, PG spots) is stored on disk with:
- **FlexBuffers** — zero-copy binary serialization, no parsing at runtime.
- **Hilbert Spatial Indexing** — maps 2D coordinates to 1D integers for
  O(log N) proximity searches.
- **Two-Level Caching** — LRU memory cache (L1) and persistent
  SpatialDiskCache (L2) backed by memory-mapped files.

## Map Rendering (MapLibre)

The map is a native MapLibre `MapView` wrapped in a `NativeMapView` Composable.
Data renders as GeoJSON sources with style layers:

- **Peer markers** — `PeerBundleBuilder` produces composite bitmap icons
  (callsign + altitude + arrow). `NativeOverlayLayers` adds them as a
  GeoJSON SymbolLayer. Peers are always the topmost layer.
- **Airspace** — `AirspaceGeoJson` + `AirspaceLayer` render polygons with
  fill and outline styles.
- **Routes** — `RouteGeoJson` + `RouteLayer` render the active task as a
  LineLayer with waypoint symbols.
- **PG spots** — `PgSpotGeoJson` + `PgSpotLayer` render site markers with
  scaling via `iconSize` interpolation expressions.

## Overlay Prioritization

`OverlayPrioritizer` (in `overlay/priority/`) scores `OverlayCandidate`
objects using distance-decay weighting. Airspace overlays use this to
decide what renders in the current viewport, keeping memory bounded by
viewport size rather than flight distance.

## Offline-First

Every runtime feature works without internet. Online connectivity is used
only for prefetch and cache population (airspaces, weather, PG spots are
silently downloaded via `UniversalCountryCacheManager`).

## Testing

Emulator tests, screenshots, and BDD scenarios exist as a truthfulness
mechanism. A feature is not done until its human test passes on real
hardware in real conditions.

## Building

```bash
git clone https://github.com/raghumad/Tern.git
cd tern-android
./gradlew assembleDebug
```

See [docs/](./docs/) for detailed architecture and subsystem documentation.
