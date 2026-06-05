# Design: Restore the Hilbert-range spatial query (+ binary index)

**Status:** Implemented. Step 1 (Hilbert-range query) = c3201c7; v2 step 1
(per-region bbox + populated centroids) = 62ff638; v2 step 2 (binary TSI2 index
with bbox-in-header + Hilbert-ordered .flex, dual-format read) = this change.
Author: 2026-06-04.
**Scope:** the shared `SpatialDiskCache` + `MapOverlayCacheUtils`. No change to
overlay composables, download pipeline, or the (already off-thread) GeoJSON build.

### Applicability (one change, three overlays)

`SpatialDiskCache` backs five caches, used two ways:

| Cache | Access | Hilbert-range query? | Binary `.idx`? |
|---|---|---|---|
| Airspace | `queryNearby` | ✅ | ✅ |
| PG-spots | `queryNearby` | ✅ | ✅ |
| Weather | `queryNearby` | ✅ | ✅ |
| Thermal hotspots | `getCachedFeatures(gridRegion)` — small cell, no spatial query | — | ✅ (shares format) |
| Routes | `getCachedFeatures(routeId)` + in-memory filter — tiny per route | — | ✅ (shares format) |

So **Step 1 (query) automatically benefits airspace, PG-spot, and weather** —
the three country-scale datasets — from one change in `queryNearby`. Hotspots
and routes don't spatial-query and are unaffected by Step 1. **Step 2 (binary
index) touches all five** (shared format), so it carries a one-time re-cache.

### Confirmed facts (from the code)

- Airspace/PG-spot/weather features are Hilbert-indexed at **`bits = 16`**
  (`OverlayGeoJsonParser`, `WeatherCache`), and `SpatialIndex.bits` defaults to
  16 → consistent. (Routes use 32 but bypass `queryNearby`.)
- Index entries are **already sorted by `hilbertIndex`** at write time
  (`cacheFeaturesStream` line 255, `createSpatialIndexAndSerialize` line 230) →
  binary-searchable as-is, no re-sort.
- Entries already carry `centroidLat/Lon` (haversine refine needs no buffer peek;
  legacy-NaN fallback retained).
- Self-similarity proven: the first `L` iterations of `hilbertXYToIndex(bits,x,y)`
  contribute exactly `hilbertXYToIndex(L, x>>(bits−L), y>>(bits−L)) << 2·(bits−L)`
  (identical rx/ry sequence, contributions scaled by `2^(2·(bits−L))`), so the
  top `2L` bits of a point's index = its level-`L` cell index. **One cell → one
  contiguous interval** holds for this exact implementation.

---

## 1. Problem

`SpatialDiskCache.queryNearby` no longer uses the Hilbert index to *prune* the
search. It loads the whole country's index and does a **haversine over every
feature** (`spatialIndex.entries.mapNotNull { ... }`), i.e. **O(N) per query**.
The Hilbert order survives only as disk-read locality for the survivors. On top
of that, the index is stored as **JSON** and `Jackson`-parsed into a `List` on
first open — a heavy one-time cost for a whole-country index (thousands of
entries).

This is a regression from the original design (Hilbert-index the map centre →
fetch the *nearby Hilbert range* → render, **O(log N + k)**), which was fast.

## 2. Why the original fast path was replaced

The original query used a **single** Hilbert window: `[H(center) − r, H(center)
+ r]`. A space-filling curve folds, so a spatial disc/box does **not** map to
one contiguous Hilbert interval — features on the far side of a fold fall
outside the window and were silently dropped (observed: Denver 29→60, Annecy
108→241). The "fix" removed the window and scanned everything to regain
correctness. **That traded the index away.** We can keep both.

## 3. Goals / non-goals

**Goals**
- O(log N + k) query that touches ~k entries, not all N.
- **Provably complete** — returns exactly the features within the radius (no
  drops). Verified against the current full-scan as a test oracle.
- Cheap cold open — binary index, no JSON parse.

**Non-goals**
- No change to the download/parse pipeline, the overlay composables, or the
  off-thread GeoJSON build (already fixed).
- Not introducing a general R-tree/quadtree; we reuse the existing Hilbert
  machinery.

## 4. Key property we rely on

`hilbertXYToIndex(bits, x, y)` (MapOverlayCacheUtils) is the standard recursive
Hilbert mapping over a **global** grid (lat→[0,1], lon→[0,1], scaled to
`2^bits`). Therefore, for any level `L ≤ bits`:

> All points inside the level-`L` cell `(xL, yL)` (the top `L` bits of `x,y`)
> have full-precision Hilbert indices in the **contiguous** interval
> `[ hL << 2·(bits−L) , (hL+1) << 2·(bits−L) )`, where
> `hL = hilbertXYToIndex(L, xL, yL)`.

This is the self-similarity of the Hilbert curve: the high `2L` bits of a
point's index equal the index of its level-`L` cell. **One cell → one
contiguous interval.** A query rectangle is covered by a small set of level-`L`
cells, hence a small set of intervals — never one, which is exactly the bug the
single-window version had.

## 5. Query algorithm

`queryNearby(center, radiusMiles, limit)`:

1. **Bounding box.** `bbox = center ± radius` (convert radius to Δlat/Δlon;
   clamp at poles / antimeridian — see §8).
2. **Pick query level `L`.** Choose the finest `L` such that the bbox spans
   ≤ `MAX_CELLS_PER_AXIS` (e.g. 8) cells per axis. Coarser L → fewer, bigger
   intervals (more over-fetch, fewer binary searches); finer L → more, tighter
   intervals. Target a total of ≲ 64 cells.
3. **Enumerate covering cells.** Map the bbox corners to level-`L` grid coords
   (same normalization as `computeHilbertIndex`), iterate the `(x,y)` cell
   rectangle. For each cell: `hL = hilbertXYToIndex(L, x, y)`, emit interval
   `[hL << 2·(bits−L), ((hL+1) << 2·(bits−L)) − 1]`.
4. **Coalesce intervals.** Sort by lo, merge overlapping/adjacent → minimal set.
5. **Binary-search the sorted index** for each interval `[lo, hi]`
   (`lowerBound(lo) … upperBound(hi)`), collect candidate entries.
6. **Haversine-refine.** Cells over-cover the disc, so filter candidates to
   `haversine(center, entry.centroid) ≤ radius`, sort by distance, `take(limit)`.
7. **Hydrate** survivors from the mmap'd `.flex` (unchanged).

Cost: `O(cells · log N + k)`. Cells ≲ 64, so ≲ 64 binary searches + a haversine
per candidate (k, not N) + k hydrations.

**Completeness argument:** every point in the bbox lies in some enumerated
level-`L` cell ⇒ its index is in that cell's interval ⇒ it is in the candidate
set ⇒ the haversine filter keeps it iff in range. No fold can drop a feature,
because we cover the rectangle with cells, not a single 1-D window.

## 6. Binary index format (`.idx`)

Replace JSON with a fixed-record binary file, sorted by Hilbert index:

```
header:  magic(4) "TSI1" | version(int) | bits(int) | count(int)
record:  hilbert(long8) | offset(long8) | length(int4) | clat(double8) | clon(double8)   // 36 B, sorted by hilbert
```

- Load = read header + the record block (or `mmap` it); **binary-search in
  place**, no object graph, no JSON parse.
- `clat/clon` already exist on `HilbertIndexEntry` (the haversine refine needs
  no buffer peek).
- Write path: `MapOverlayCacheUtils.createSpatialIndexAndSerialize` /
  `cacheFeaturesStream` sort entries by hilbert (they largely are) and emit the
  binary block instead of `objectMapper.writeValueAsBytes`.

## 7. Backward compatibility / migration

- Bump a `CACHE_SCHEMA_VERSION`. The new `.idx` starts with magic `TSI1`; old
  JSON `.idx` files fail the magic check → treated as **not cached** → the
  country re-downloads once. Airspace/PG data is re-downloadable, so this is an
  acceptable one-time cost per country (and only on first open of a stale
  cache). `validateCacheIntegrity` gets a magic/version check.

## 8. Edge cases

- **Antimeridian / poles:** clamp the bbox to valid ranges; if it crosses ±180°
  lon, split into two boxes. Paragliding regions rarely hit this, but the code
  must not produce an inverted cell rectangle.
- **Empty / tiny cache:** `count == 0` → return empty (no change).
- **Legacy entries without `clat/clon`:** not possible under the new format (we
  re-download); the JSON fallback path is removed with the format bump.
- **`limit`/budget:** unchanged — applied after the distance sort.

## 9. Correctness strategy (this is what replaces "Phase A")

Keep the current full-scan haversine as the **oracle** in tests:

- **Property test:** for a realistic dataset (≥ a few hundred polygons across a
  wide region) and N random (center, radius) queries, assert
  `hilbertRangeQuery(c, r).toSet() == fullScanHaversine(c, r).toSet()`. Any
  dropped/extra feature fails. This is the regression guard against
  reintroducing the fold bug.
- **Unit tests** for the interval math: cell→interval contiguity, coalescing,
  antimeridian split.
- Existing `AirspaceUXTest` (on-map render) must stay green.
- Optional: one before/after device capture of cold-open + warm-query time on
  the real US cache, purely as proof of the speedup (not a gate).

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Re-introduce dropped features | Oracle property test vs full-scan (§9) — the central gate |
| Interval explosion for huge bbox | Cap cells per axis; coarsen `L`; intervals coalesce |
| Binary format bugs / corruption | Magic+version+count header; integrity check; re-download on mismatch |
| One-time re-download of all cached countries | Acceptable; data is re-downloadable; happens once |
| Hilbert MSB-cell assumption wrong for this impl | Unit test: `topBits(hilbertXYToIndex(bits,x,y)) == hilbertXYToIndex(L, x>>(bits-L), y>>(bits-L))` for random x,y |

## 11. Implementation plan — split into two steps

**Step 1 — Hilbert-range query (zero migration, ships the big win):**
1. `MapOverlayCacheUtils`: pure helpers — `hilbertCoveringIntervals(center,
   radiusMeters, bits, maxCellsPerAxis)` (bbox → level-L cells → intervals →
   coalesce, with antimeridian split), and `lowerBound`/`upperBound` over a
   sorted `List<HilbertIndexEntry>`.
2. `SpatialDiskCache.queryNearby`: use the intervals to gather candidates,
   haversine-refine, sort, `take(limit)`, then hydrate (existing code).
   **Keep the old scan as private `queryNearbyFullScan` — the oracle.**
3. Tests (§9): MSB-cell property test (run first), interval unit tests,
   oracle property test (`hilbertRange == fullScan` over random queries incl.
   antimeridian), keep `AirspaceUXTest` green.
4. Build, unit + `AirspaceUXTest`, optional device before/after capture.

Reads the **existing JSON `.idx`** unchanged — no format change, nothing
re-downloads. Benefits airspace + PG-spot + weather immediately.

**Step 2 — binary `.idx` (migration-bearing, cold-open win):**
5. Binary index read/write + `CACHE_SCHEMA_VERSION` bump + magic/version in
   `validateCacheIntegrity`; old JSON caches re-download once (all five caches).
6. Same oracle/integrity tests must still pass.

---

## Cache format v2 (Step 2, expanded — device-driven)

Field RCA on real caches showed the costs are: (a) `queryAllCachedNearby`
loading/querying **all** cached countries on a cold pan (8 × ~400 KB JSON index
parse), and (b) the stream writer leaving index centroids **NaN**, forcing a
buffer peek per candidate. v2 fixes both and adds Hilbert-ordered features for
sequential survivor reads. Re-download on version bump is accepted.

### `.idx` v2 — binary, header + sorted records

```
magic        : 4 bytes  "TSI2"
version      : int32    (= CACHE_SCHEMA_VERSION)
bits         : int32    (16)
count        : int32
writtenAtMs  : int64    (self-describing freshness; lifetime still per cache type)
minLat,minLon,maxLat,maxLon : 4 × float64   ← country DATA bbox
─ records (count × 32 B, ascending by hilbertIndex) ─
hilbertIndex : int64 | byteOffset : int32 | byteLength : int32 | clat : float64 | clon : float64
```

- Header is fixed-size → read the **bbox + count cheaply** without parsing all
  records (the key to skipping irrelevant countries).
- Records carry `clat/clon` (populated, no more NaN) → in-memory haversine
  refine, no buffer peek.
- Sorted by `hilbertIndex` → `queryHilbertRange` binary-searches directly.

### `.flex` v2 — features in Hilbert order

Both write paths (`cacheFeatures`, `cacheFeaturesStream`) emit features sorted
by `hilbertIndex`, so a query's survivors (a contiguous Hilbert interval) are a
**contiguous byte range** → sequential `mmap` reads. The stream path buffers
serialized feature bytes (≈ the .flex size, ~31 MB for US — fine on IO), sorts,
then writes.

### Query changes

- `getRegionBbox(regionId)`: read just the v2 header (≈ 80 B). Cheap.
- `queryAllCachedNearby`: build the query bbox (center ± radius); for each
  cached country, skip unless its header bbox intersects → **query only the
  relevant country/countries** (DC → US only; Annecy → FR/CH/IT only). No
  network, no geocode, no scan-all.
- `queryNearby`: unchanged algorithm; now hits the populated-centroid fast path.

### Migration

`CACHE_SCHEMA_VERSION` bump; `validateCacheIntegrity` checks the `TSI2` magic.
Old JSON `.idx` → invalid → that country re-downloads once (all five caches).
Per-type lifetimes (airspace ~90 d, weather hours, route permanent) unchanged.

### Tests

`queryHilbertRange` oracle still gates correctness (unchanged). Add: binary idx
round-trip (write→read identity), header-bbox read, `queryAllCachedNearby` bbox
skip (irrelevant countries not loaded), Hilbert-order flex monotonic offsets.
On-device: DC loads only US; cold pan no longer parses 8 indexes.

### Out of scope (separate investigation)

Storing features as **vector tiles (MVT)** so MapLibre renders without the
per-query `toFeatureCollection` build — a larger tiling change; v2 already
attacks cold-load + fetch tail latency.

## 12. Why not just keep the O(N) scan?

Warm, the scan is only a few ms over a few-thousand features — but the **cold
JSON parse** of a whole-country index is not, and the architecture throws away
the index you built. Restoring the Hilbert-range query fixes both, and the
oracle test means we get the original speed **without** the original lossiness.
