# Overlay infrastructure audit

Findings from reading the overlay system, triaged through the safety
lens. The guiding question: **if this fails during flight, does the
pilot lose information they need to stay safe?**

## Priority 1 — Safety (affects pilot's ability to see critical info)

### S1. Overlay lifecycle doesn't prevent memory accumulation (root cause of OOM risk)

**What's wrong:** Overlays (airspaces, PG spots, weather markers,
ViewToBitmap bitmaps) are loaded as the pilot flies but never
aggressively unloaded when they leave the vicinity. During an 11-hour
XC flight covering 100+ km, memory grows continuously.

**Why it's safety:** If the phone OOMs, the app crashes. The pilot
loses their flight computer entirely — airspace warnings, peer
awareness, navigation, everything. A crash mid-flight is the worst
possible outcome.

**Root causes to fix:**
- No viewport-based eviction: overlays outside the visible map area
  should be unloaded on every significant map move, not kept "in
  case the pilot pans back."
- L1 in-memory cache (LRU) may not have a hard cap. If it doesn't,
  it grows until the system kills the process.
- ViewToBitmap-generated marker bitmaps aren't recycled when the
  marker leaves the viewport. Each bitmap is a chunk of heap.
- The "adaptive overlay system" zone budgets exist but may not be
  enforced at load time — only at emergency-cleanup time, which is
  too late.

**Fix:** Enforce bounded caching with hard LRU caps. Evict overlays
that leave the viewport + a margin. Recycle bitmaps. Enforce zone
budgets at load time, not just at cleanup time. After 11 hours of
XC, memory usage should be flat, not growing.

**Verification:** A soak test that replays a long IGC flight and
monitors heap growth over time. If heap grows continuously, the fix
isn't working.

### S2. Emergency cleanup is a safety net, not the primary defense

**What's wrong (was #7):** The emergency cleanup (~300 lines, now
rewritten to ~25 lines) fires when the system signals low memory.
It sheds overlays from farthest to nearest.

**Reframing:** The emergency cleanup should almost never fire. If
it does, it means S1 (the real fix) has a bug. The cleanup should
log a warning when it triggers — treat it like a crash report,
not normal operation.

**Current state:** Rewritten and tested (commit c0b65de). The
rewrite is correct and simple. But it still fires routinely if S1
isn't fixed. Fix S1 first; keep S2 as the fallback.

**Future improvement (per user insight):** If overlays are
maintained in importance-sorted order at insertion time, the
emergency cleanup becomes `removeLast()` in a loop — no zone
logic needed. The data structure does the work.

### S3. Never-evict rules for safety-critical overlay types

**What's wrong:** No explicit rule prevents the eviction of
overlays that are critical for immediate pilot safety:
- **Nearby airspaces (CORE/NEAR)** — entering controlled airspace
  is illegal and dangerous. These must NEVER be shed.
- **Peer markers (Mezulla)** — few in number (max ~20); the whole
  point of Mezulla. Never shed.
- **The active route** — pilot's navigation. Never shed.

**Why it's safety:** If the emergency cleanup (S2) sheds a nearby
airspace boundary, the pilot might fly into restricted airspace
and collide with commercial traffic.

**Fix:** Tag overlay types with `canEvict: Boolean`. Airspace
CORE/NEAR, all peer markers, and the active route are
`canEvict = false`. The cleanup loop skips them.

### S4. Airspace z-ordering bypass — FIXED

Airspace polygons were rendered directly on `map.overlays` at
index 0, bypassing the FolderOverlay system. Fixed in commit
5696516. Airspaces now render in their own FolderOverlay with
correct z-ordering: routes (bottom) → airspaces → PG spots →
Mezulla peers (top).

**Note from the fixing agent:** MezullaOverlayManager also adds
markers directly to `map.overlays` instead of through its
FolderOverlay. Same class of bug but lower z-ordering consequence
(peers are the top layer). Should be fixed for consistency.

### S5. Silent action drop in MapStore — FIXED

Unknown action types were silently swallowed. Fixed in commit
914e8d3: `TernAction` marker interface introduced; `else` branch
now logs `Log.w`. Debug-mode `error()` was too aggressive (caused
test leakage) and was downgraded to log-only in ccbf1d8.

### S6. PerformanceDebugger on the dispatch hot path

**What's wrong (#14 from original audit):** A `try/catch` around
`PerformanceDebugger.recordStateUpdate()` runs on every batched
action dispatch. In release builds this is wasted cycles.

**Why it's safety:** Under high load (many peers + weather +
airspaces updating simultaneously), accumulated overhead causes
frame drops. Janky rendering makes critical info harder to read
at a glance during turbulent flight.

**Fix:** Gate behind `BuildConfig.DEBUG` so release builds pay
zero cost. One-line change.

## Priority 2 — Code quality (doesn't directly affect pilot safety)

### Q1. OverlayType enum config doesn't scale (#1)
`OverlayState` has per-field config for 3 types but enum has 4.
Fix: `Map<OverlayType, OverlayConfig>`.

### Q2. Inconsistent sealed class vs sealed interface (#3)
`WeatherActions` is sealed class, `PeerAction` is sealed
interface. Cosmetic. Standardize on sealed interface.

### Q3. PGSpotOverlayManager is 1300 lines (#6)
Mixes weather orchestration with marker lifecycle. Fix: split
into PGSpotOverlayManager + PGSpotWeatherOrchestrator.

### Q4. OverlayCoordinator has hardcoded type checks (#8)
`addOverlayManager` checks concrete types for country-cache
injection. Fix: `CountryCacheAware` interface.

### Q5. Dead code (#9, #10, #11)
`OverlayActions` sealed class is never dispatched — delete.
`RankingTier` and `SpatialLattice` are actually used by
`RouteOverlayManager` (the #7 agent confirmed they're NOT
dead code — the original audit was wrong).

### Q6. Duplicate comments, stale doc comments (#12, #13)
Cosmetic cleanup.

### Q7. PGSpotOverlayManagerTest regression from S2 rewrite
`performMapMove ignores 0,0 coordinates` fails with
`UncaughtExceptionsBeforeTest` after the emergency cleanup
rewrite. Was passing before. Needs investigation — likely a
lifecycle interaction in the test's coroutine scope.

## Prioritized action plan

| Priority | Item | Effort | Can parallelize? |
|----------|------|--------|-----------------|
| **1** | **S1: Fix overlay lifecycle (prevent OOM)** | Large — touches eviction, caching, bitmap recycling | Yes (independent of Mezulla work) |
| **2** | **S3: Never-evict rules for safety-critical overlays** | Small — `canEvict` flag + skip in cleanup loop | Yes (after S1 design is clear) |
| **3** | **S6: Gate PerformanceDebugger behind DEBUG** | Tiny — one-line change | Yes |
| **4** | **S4 follow-up: Fix MezullaOverlayManager FolderOverlay bypass** | Small | Yes |
| **5** | **Q7: Fix PGSpotOverlayManagerTest regression** | Small-medium (investigation needed) | Yes |
| 6 | Q3: Split PGSpotOverlayManager | Medium | Yes |
| 7 | Q1: OverlayState as Map | Small | Yes |
| 8 | Q4: CountryCacheAware interface | Small | Yes |
| 9 | Q2, Q5, Q6: Cosmetic cleanup | Small | Yes |

S1 is the biggest and most important. Everything else is either
already fixed (S4, S5) or small enough to parallelize.
