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

**Status: NOT STARTED — highest priority safety item.**

The current code accumulates overlays as the pilot flies and doesn't
discard them when they leave the viewport. During an 11-hour XC,
memory grows until the app OOMs. The fix is architectural:

- On every significant viewport change (pan, zoom, GPS update):
  query the Hilbert cache for "what's within viewport + margin?"
  Render those. Discard anything not in the result set.
- Enforce zone budgets at load time, not at cleanup time.
- Recycle ViewToBitmap bitmaps when markers leave the viewport.
- Bound the L1 in-memory LRU cache with a hard cap.

**Verification:** a soak test that replays an 11-hour IGC flight
(we have the Aravis data — ~11 hours) and monitors heap growth.
Heap should be flat, not growing.

### S2. Emergency cleanup as a last-resort safety net

**Status: DONE (commit c0b65de, rewritten to 25 lines).**

Sheds overlays farthest-to-nearest when memory pressure is
detected. If this ever fires in production, it means S1 has a bug.
Logs a warning when triggered — treat as a bug report.

Future improvement: if overlays are maintained sorted by importance
at insertion time, cleanup becomes `removeLast()` in a loop.

### S3. Airspace z-ordering — FIXED

**Status: DONE (commit 5696516).**

Airspace polygons now render in their own FolderOverlay with correct
z-ordering: routes → airspaces → PG spots → Mezulla peers (top).

**Follow-up:** MezullaOverlayManager also bypasses FolderOverlay
(adds markers directly to map.overlays). Same fix needed.

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
| Q3 | PGSpotOverlayManager is 1300 lines | Split: marker lifecycle + weather orchestration | Medium |
| Q4 | OverlayCoordinator hardcoded type checks | `CountryCacheAware` interface | Small |
| Q5 | OverlayActions sealed class is never dispatched | Delete | Tiny |
| Q6 | Duplicate/stale comments | Clean up | Tiny |
| Q7 | PGSpotOverlayManagerTest regression from S2 | Investigate coroutine scope leak | Small |

## Prioritized action plan

| Priority | Item | Why | Effort |
|----------|------|-----|--------|
| **1** | **S1: Reactive overlay rendering** | Prevents OOM crash in flight. The root cause. Everything else is a band-aid without this. | Large |
| **2** | S5: Gate PerformanceDebugger | Frame drops affect glanceability in turbulence | One-line |
| **3** | S3 follow-up: MezullaOverlayManager FolderOverlay bypass | Consistency with the fix we already made for airspaces | Small |
| **4** | Q7: PGSpotOverlayManagerTest regression | Our safety fix broke an existing test — should be cleaned up | Small |
| 5+ | Q1–Q6 | Code quality; fix when convenient | Various |

## Future considerations

- **Peer positions should be logged during flight recording** for
  post-flight analysis and incident reconstruction. The logged data
  also becomes real-world test fixtures for the swarm simulator.
  See [[project-tern-log-peers-in-flight-recording]].
