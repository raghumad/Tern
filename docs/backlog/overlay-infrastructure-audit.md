# Overlay infrastructure audit

## The architectural principle

Tern's spatial data (airspaces, PG spots, weather) is already cached
on disk as FlatBuffers with Hilbert spatial indexing and memory-mapped
access. It's never lost. It's never slow to query (O(log N),
microseconds).

Overlays on the map are **rendering objects** (polygons, markers),
not data. Evicting an overlay doesn't delete anything — it frees heap.
Re-rendering it is a spatial query + object creation, both cheap.

This means:

> **Overlays should be a reactive projection of the spatial cache
> onto the current viewport. Created on demand when the viewport
> includes them. Discarded when it doesn't. Memory is bounded by
> viewport size, not by flight distance.**

If the codebase follows this principle, there is no memory
accumulation, no emergency cleanup needed, and no concept of
"safety-critical overlays that must never be evicted" — because
nothing is permanently evicted. Everything nearby is always
re-rendered from the cache on every viewport change.

## What "safety critical" actually means

NOT: "keep this rendering object in memory forever."
(That causes the OOM problem.)

ACTUALLY: **"if the pilot is near this, it must be visible on the
screen — always, immediately."** Since the cache is always on disk,
this is guaranteed by querying and rendering on every viewport
change, not by holding objects in memory.

## Overlay types and their lifecycles

| Type | Source of truth | Correct lifecycle |
|------|----------------|------------------|
| Airspaces | Disk cache (FlatBuffer, mmap) | Render nearby on viewport change; discard when out of view |
| PG spots | Disk cache (same) | Same |
| Weather | Disk cache (same) | Same |
| Peers (Mezulla) | Redux state (from LoRa) | Always in memory — max ~20. No eviction. |
| Active route | Redux state | One object. Always in memory. |

Peers and the route don't need eviction (tiny, bounded). Spatial
data doesn't need importance-based eviction because re-rendering
from disk is microseconds.

## Safety fixes — status

### S1. Make overlay rendering reactive (the real fix)

**Status: IMPLEMENTED** — `OverlayPrioritizer` (in `overlay/priority/`
package) scores `OverlayCandidate` objects using distance-decay
weighting. Used by `AirspaceOverlay` to decide what renders in the
current viewport. Memory is bounded by viewport size, not flight
distance.

Not yet verified via soak test (replaying an 11-hour IGC flight to
confirm heap stays flat). That remains the acceptance criterion.

### S2. Emergency cleanup as a last-resort safety net

**Status: DONE (commit c0b65de, rewritten to 25 lines).**

Sheds overlays farthest-to-nearest when memory pressure is
detected. If this ever fires in production, it means S1 has a bug.
Logs a warning when triggered — treat as a bug report.

Future improvement: if overlays are maintained sorted by importance
at insertion time, cleanup becomes `removeLast()` in a loop.

### S3. Airspace z-ordering — FIXED

**Status: DONE (commit 5696516).**

Airspace polygons now render via GeoJSON layers on native MapLibre.
Z-ordering is controlled by `style.addLayer()` order — peers are
the topmost layer.

**Follow-up (DONE):** Dead Compose overlay code (MezullaPeerLabels,
PeerMarkerComposable, MezullaPeerCircles) deleted in commit 59c98e1.

### S4. Silent action drop in MapStore — FIXED

**Status: DONE (commit 914e8d3 + ccbf1d8).**

`TernAction` marker interface; `else` branch logs `Log.w`.

### S5. PerformanceDebugger on dispatch hot path

**Status: NOT STARTED — one-line fix.**

Gate `PerformanceDebugger.recordStateUpdate()` behind
`BuildConfig.DEBUG`. Release builds pay zero cost.

## Code quality items (not safety-affecting)

| # | Finding | Fix | Effort |
|---|---------|-----|--------|
| Q1 | OverlayState per-field config doesn't scale | `Map<OverlayType, OverlayConfig>` | Small |
| Q2 | Inconsistent sealed class vs sealed interface | Standardize on sealed interface | Small |
| Q3 | ~~PGSpotOverlayManager is 1300 lines~~ — replaced by `PgSpotGeoJson` + `PgSpotLayer` (MapLibre composables) | No longer applicable | N/A |
| Q4 | ~~OverlayCoordinator hardcoded type checks~~ — OverlayCoordinator removed | No longer applicable | N/A |
| Q5 | OverlayActions sealed class is never dispatched | Delete | Tiny |
| Q6 | Duplicate/stale comments | Clean up | Tiny |
| Q7 | PGSpotOverlayManagerTest regression from S2 | Investigate coroutine scope leak | Small |

## Prioritized action plan

| Priority | Item | Why | Effort |
|----------|------|-----|--------|
| **1** | **S1 soak test verification** | OverlayPrioritizer is implemented but needs an 11-hour IGC replay to confirm heap stays flat | Medium |
| **2** | S5: Gate PerformanceDebugger | Frame drops affect glanceability in turbulence | One-line |
| **3** | Q7: PGSpotOverlayManagerTest regression | Our safety fix broke an existing test — should be cleaned up | Small |
| 4+ | Q1–Q2, Q5–Q6 | Code quality; fix when convenient | Various |

## Future considerations

- **Peer positions should be logged during flight recording** for
  post-flight analysis and incident reconstruction. The logged data
  also becomes real-world test fixtures for the swarm simulator.
  See [[project-tern-log-peers-in-flight-recording]].
