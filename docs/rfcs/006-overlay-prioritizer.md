# RFC 006: Unified Overlay Prioritizer

## Status: Accepted
## Date: 2026-05-24

## Problem

The pilot's map has a budget — memory, GPU, visual clarity, and most
importantly, pilot attention. There are potentially thousands of
overlay candidates (airspaces, PG spots, peers, route waypoints,
weather markers) competing for that budget. The question "what does
the pilot see?" needs a single, unified answer that works across all
overlay types.

The previous approach (per-type zone budgets in `AdaptiveOverlaySystem`)
had three problems:
1. Each overlay manager independently managed its own budget. No
   cross-type priority: 1000 PG spots could crowd out 5 critical
   airspaces.
2. Priority was implicit in zone membership (CORE/NEAR/FAR), not
   scored explicitly against other types.
3. Budget enforcement happened at render time (too late) rather
   than at data-loading time (where it belongs).

## Solution

A single `OverlayPrioritizer` that sits between the spatial caches
and the renderer. It sees ALL candidates from ALL types, scores each
with a unified priority formula, sorts, and emits the top N. The
renderer (MapLibre) gets only what the prioritizer chose.

```
AirspaceCache ──┐
PGSpotCache   ──┤
PeerState     ──┤──→ OverlayPrioritizer ──→ MapLibre GeoJSON sources
RouteState    ──┤    (score, sort, top N)
WeatherCache  ──┘
```

## Scoring

Every overlay candidate implements one interface:

```kotlin
interface OverlayCandidate {
    val kind: OverlayKind
    val position: Position
    fun score(pilotPosition: Position): Double
}
```

The default scoring formula:

```
score = safetyWeight(kind) × distanceDecay(distance)
```

Where `distanceDecay(d) = 1.0 / (1.0 + d_km)` — nearby scores
high, far scores low, never zero.

### Safety weight table

```kotlin
enum class OverlayKind(val safetyWeight: Int) {
    SOS_ALERT(1000),
    AIRSPACE(100),
    PEER(80),
    ROUTE_WAYPOINT(60),
    PG_SPOT(20),
    WEATHER_MARKER(10),
}
```

### Custom scoring

Any candidate can override `score()` for type-specific logic:

```kotlin
class GustFrontCandidate(...) : OverlayCandidate {
    override fun score(pilotPosition: Position): Double {
        // severity × time urgency × distance decay
        return severity * (1.0 / (1.0 + minutesUntilArrival)) *
               distanceDecay(distanceTo(pilotPosition))
    }
}
```

The prioritizer doesn't know or care about the formula. It sorts
by `score()` and takes top N. Custom scoring is per-type, budget
enforcement is universal.

## Budget

The budget is one number: how many overlay candidates reach the
renderer. Default: 300 (tunable per device class).

```kotlin
class OverlayPrioritizer(
    private val budget: Int = 300,
) {
    fun prioritize(
        candidates: List<OverlayCandidate>,
        pilotPosition: Position,
    ): List<OverlayCandidate> {
        return candidates
            .map { it to it.score(pilotPosition) }
            .sortedByDescending { it.second }
            .take(budget)
            .map { it.first }
    }
}
```

That's the entire prioritizer. Score, sort, take top N. The data
structure does the work.

### Emergency cleanup = lowering the budget

When `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` fires:

```kotlin
prioritizer.budget = prioritizer.budget / 2
```

Same code path, different N. No separate cleanup mechanism.

### Budget recovery

When memory pressure eases, restore the original budget. The next
viewport update re-queries with the full budget and the missing
overlays reappear (they were never deleted from the cache — just
excluded from the render set).

## Extensibility

Adding a new overlay type:
1. Add one entry to `OverlayKind` enum (one line).
2. Write a candidate producer that queries the relevant cache and
   wraps results as `OverlayCandidate` (one class).
3. Feed the candidates to the prioritizer alongside the others.
4. The prioritizer automatically includes them in the budget
   competition. No changes to the prioritizer itself.

If the new type needs custom scoring (gust front, NOTAM, thermal
hotspot), override `score()` in the candidate class. The prioritizer
still just sorts and takes top N.

## What this replaces

| Deleted | Replaced by |
|---------|-------------|
| `AdaptiveOverlaySystem` (zone budgets) | `OverlayPrioritizer.budget` (one number) |
| `BaseOverlayManager` overlay lifecycle (600+ lines) | Candidate producers feed the prioritizer |
| `EmergencyCleanupResult` | `prioritizer.budget /= 2` |
| `preserveSafetyCriticalOverlays()` | High `safetyWeight` entries naturally survive budget cuts |
| `RankingTier` | `OverlayKind.safetyWeight` |
| Zone-based distance budgets (CORE/NEAR/FAR/EXTREME) | `distanceDecay()` in the score formula |
| Per-manager budget enforcement | One prioritizer, one sorted list |

## Rendering layer (MapLibre)

The prioritizer outputs a list of scored candidates. A thin adapter
groups them by `OverlayKind` and feeds each group to the appropriate
MapLibre source:

- `OverlayKind.AIRSPACE` → `airspaceSource` → `FillLayer`
- `OverlayKind.PEER` → `peerSource` → `SymbolLayer`
- `OverlayKind.PG_SPOT` → `pgSpotSource` → `SymbolLayer`
- etc.

Z-ordering (which layer renders on top) is still layer-order in
MapLibre, separate from the budget priority. A PG spot that made
the budget cut still renders below a peer that also made the cut.

## Testing

The prioritizer is a pure function: candidates in, top-N out.
Test cases:

- 1000 PG spots + 10 nearby airspaces + 3 peers, budget 50:
  all 3 peers survive, all 10 airspaces survive, 37 nearest PG
  spots fill the rest. No airspace or peer is ever dropped before
  all distant PG spots are gone.
- Budget halved (memory pressure): same ordering, fewer PG spots.
  Peers and airspaces still survive.
- Custom-scored gust front with high urgency outranks a distant
  airspace. Correct — the pilot needs to know about the approaching
  gust front more than a far-away airspace.
- Zero candidates: empty list. No crash.
- Budget > candidates: all render. No special case.

## Open questions

- **Exact `distanceDecay` shape.** `1/(1+d)` is simple but drops
  steeply. Alternatives: `1/(1+d^0.5)` (gentler), `exp(-d/50)`
  (exponential with 50km half-life). Pick based on what "feels
  right" when flying. Tunable without changing the architecture.
- **Budget per device class.** 300 is a guess. Measure on the
  target device (Pixel, mid-range Samsung) and adjust.
- **Should route waypoints be exempt from the budget?** The active
  route is one object with ~5-10 waypoints. Probably exempt (too
  few to matter, always needed). But the prioritizer handles it
  correctly either way — high weight + close distance = always
  survives.
