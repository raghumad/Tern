# Tern — Claims & how we prove them

> **Status: DRAFT v0** — this is the thing we test. Edit freely: add/remove/reword
> claims, fix axes, correct tolerances. Nothing is torn down or built against this
> until it reads true to you.

## Why this doc exists

We are replacing the screenshot/BDD test suite with **claim-driven capability
tests**. The unit of testing is a **claim** — a promise Tern makes to a pilot —
not a screen. A claim test sets up realistic flight conditions, replays a real
flight, controls the environment (connectivity, faults), and asserts the claim
holds via **observable behavior** (cache hits, store state, warnings fired, peer
awareness, no-crash) — never pixels. The output is a **Claims Report**:
`HELD / BROKEN + evidence`, deterministic, emulator-free where possible.

A test earns its place only if a broken result would stop you shipping.

## The slogan, made testable

> *"Tern — a very intuitive paragliding app"* · *"removing unknowns from flying"*

For Tern, **intuitive = the pilot always has the knowns they need — correct, in
time, without fiddling, without signal, and without the app ever breaking.**

That decomposes into a matrix: the **knowns** a pilot must never have to wonder
about, each judged on five **axes**.

### The knowns (rows)

| # | Known — "I never have to wonder…" | Backed by |
|---|---|---|
| K1 | where I am / what's below me | offline map + tiles |
| K2 | what airspace I'm in / approaching | cached airspace polygons |
| K3 | where I can land / fly (sites) | PG spots |
| K4 | the wind / weather hazard here | weather overlays |
| K5 | where my team is & their state | Mezulla LoRa peer HUD |
| K6 | how my task is going | route / FAI task |

### The axes (columns)

| Axis | The promise | Tested by |
|---|---|---|
| **Correct** | the info is accurate | replay + assert against source data |
| **Timely** | surfaced *when* needed (warn before the boundary) | replay + assert event timing |
| **Frictionless** (= *intuitive*) | appears proactively; pilot never hunts/configures | replay + assert it happened with no user action |
| **Offline** | true with zero connectivity | replay with network cut |
| **Resilient** | degrades on fault; never breaks, never cascades | replay + **fault injection** |

## Cross-cutting principles

- **P1 — Offline-first.** No in-air operation requires connectivity. Online is
  used *only* for prefetch/cache. Test: cut the network for the whole flight;
  every known still works.
- **P2 — Graceful degradation ("never breaks, only degrades").** Any subsystem
  fault degrades *only* its own feature (stale shown with indicator, or hidden) —
  no crash, no ANR, no frozen UI, no cascade to other knowns. The app stays
  usable. *Rationale: a crash mid-flight is the worst unknown of all.*

## The test vehicle: replay a real flight

The README already verifies Mezulla by **replaying real flights (Aravis, Edith's
Gap, Bir Billing)**. That is our backbone for *every* claim:

1. **Replay** a recorded IGC track through the app's data/logic stack (position by
   position, real time-compressed). For team claims, replay several tracks.
2. **Control the environment** — network on/off; inject faults (expire cache,
   corrupt a file, throw inside a subsystem) at chosen points on the track.
3. **Assert observable behavior** — cache hit/miss counts, store state, warnings
   fired and their timing, peer awareness values, and the invariant *no crash /
   no ANR*. No screenshots.

Deterministic (recorded track), pilot-authentic (a real XC), mostly off-emulator
(data + logic). The GL surface is *not* under test here.

## The claims (draft — fill the cells that matter)

Each line is one falsifiable claim. `[ ]` = not yet written. Tolerances are
placeholders for you to set.

### K1 — Position & terrain
- **Offline:** map tiles for the flight corridor render with no network. `[ ]`
- **Frictionless:** map auto-centers on the pilot and follows during flight. `[ ]`
- **Resilient:** GPS dropout → last-known shown + "stale" indicator; app keeps
  running. `[ ]`

### K2 — Airspace  *(subsystem mapped 2026-06; JVM-testable except Timely)*
- **Correct:** queried/rendered polygons match source for the viewport.
  `AirspaceGeoJson.resolveAirspaceClass` (icaoClass 0-indexed), `queryHilbertRange`
  (haversine refine). `[HELD]` — `AirspaceClaimsTest.correct` (real OpenAIP Class-C
  TMA round-trip), `AirspaceGeoJsonTest`, `HilbertSpatialQueryTest`. *Nice-to-have:*
  a haversine-vs-brute-force oracle over random points.
- **Timely:** awareness of airspace ahead fires *before* the boundary. `[GAP]`
  **Not implemented** — the query is purely reactive (static 200 km horizon on
  centre change); no lookahead/prediction exists. This is a feature to build, not
  a test to write.
- **Frictionless (memory-safe declutter):** dense airspace stays bounded by the
  budget so it can **never OOM** (dragging into dense Europe did, before budgeting
  was added), keeping the nearest; tightens under memory pressure.
  `OverlayPrioritizer.prioritize` (budget 300, distance-decay). `[HELD]` —
  `AirspaceClaimsTest.frictionless` (3000 in → 300 out → 150 under pressure).
- **Offline:** airspace along the corridor served from cache; **0 network calls**.
  `AirspaceCache.queryAllCachedNearby` (no network). `[HELD]` —
  `AirspaceClaimsTest.offline` across 5 locations (both hemispheres, both longitude
  signs, near the antimeridian).
- **Resilient:** *stale/corrupt/missing airspace → hides or shows stale; map,
  route, peers, weather keep working; no crash.* ← canonical degradation test.
  `SpatialDiskCache.validateCacheIntegrity` + `queryNearby` (catches → empty).
  `[HELD]` — `AirspaceClaimsTest.resilient` ×6: missing region, corrupt index,
  missing `.flex`, stale (>90d), truncated `.flex`, and **no-cascade** (an airspace
  fault leaves PG spots untouched). Degenerate geometry (<3 vertices) dropped —
  `AirspaceGeoJsonTest`.

### K3 — Sites / landability (PG spots)  *(JVM, via PGSpotCache + PgSpotGeoJson)*
- **Correct:** a site's name survives the download→cache→query round-trip and
  resolves for the marker (nested `properties.name` — flat reads "" and renders
  nothing). `[HELD]` — `SitesClaimsTest.correct`.
- **Frictionless:** nearby sites surface by *location*, not a search; a distant
  region's sites don't. `[HELD]` — `SitesClaimsTest.frictionless`.
- **Offline:** spots for the region served from cache; **0 network calls**.
  `[HELD]` — `SitesClaimsTest.offline` (corridor).
- **Resilient:** missing/corrupt spot data → empty/refused, no crash (shares the
  airspace cache fault catalog). `[HELD]` — `SitesClaimsTest.resilient`.

### K4 — Weather / wind  *(JVM, via WeatherAPI + WeatherCache + hazard logic)*
- **Correct:** hazard thresholds classify right — CAPE>500→convective; lightning
  >60%→thunderstorm (outranks); cloud&humidity proxy→convective; benign→no false
  alarm. `[HELD]` — `WeatherClaimsTest.correct`.
- **Timely:** weather interpolated to the waypoint arrival time (circular wind
  direction, linear CAPE). `[HELD]` — `WeatherClaimsTest.timely`.
- **Offline:** a forecast cached pre-flight is retrievable with no network; data
  >4h is flagged stale. `[HELD]` — `WeatherClaimsTest.offline` + `.resilient`.
- **Resilient:** a degraded surface-only source (no CAPE/lightning) raises no false
  hazard and never crashes. `[HELD]` — `WeatherClaimsTest.resilient`.
- **Flyability:** a transparent "is it flyable here, now and soon" read — GO /
  CAUTION / NO_GO with the reasons shown (wind, gusts, convective, storm,
  visibility), plus the next worsening time, plus a *quality* read (thermal
  strength from lapse rate, inversion cap, cloudbase, overcast). `[HELD]` —
  `WeatherClaimsTest` (flyability ×2 · visibility · quality); `weather/Flyability.kt`,
  configurable limits, verdict = worst factor.
- **Wind detail:** a clean gust *factor* (gust − 10 m), low-level gradient
  (80 m − 10 m), and precip probability — now that `WeatherData` + parser carry the
  10 m wind + precip (Jackson-serialized, ETA-interpolated). `[HELD]` —
  `WeatherClaimsTest` (gust factor & gradient · precip). *Also fixed:* visibility
  was compared as metres but the model stores km (latent false-no-go).
- **Thermal outlook:** lapse rate → expected climb rate / overdevelopment time.
  `[GAP]` Lapse rate is computed but never turned into a thermal forecast.
- **Source policy:** per-country best free model (HRRR/AROME/ICON-D2) + refresh
  cadence, with an independent fallback when Open-Meteo is down. `[HELD, logic]` —
  `WeatherClaimsTest` (source policy · fallback): `WeatherSourcePolicy` (country →
  model + TTL) and `FallbackWeatherAPI` (primary → secondary → cache, never throws).
- **Source wiring:** apply the policy to the live fetch (model into the URL keyed
  off the detected country) and implement the MET Norway secondary provider.
  `[GAP]` The logic above exists + is tested, but the live fetch still uses
  `best_match` with no fallback until it's threaded through.
- **Stability diagram:** the Skew-T plot. `[GAP]` The stability math is computed;
  the diagram itself is a text placeholder.

### K5 — Team (Mezulla)
- **Correct:** each peer's position / rel-altitude / heading / distance correct. `[ ]`
- **Timely:** updates live; staleness shown; off-screen buddies via edge markers. `[ ]`
- **Offline:** works over LoRa mesh with no internet at all. `[ ]`
- **Resilient:** peer dropout → ages out / marked stale; HUD + map never crash. `[ ]`

### K6 — Route / task  *(JVM, via RouteCache + RouteIOManager)*
- **Correct:** task geometry — each waypoint's position and FAI cylinder radius —
  round-trips through persistence exactly. `[HELD]` — `RouteClaimsTest.correct`.
  *To add:* FAI-triangle detection (known 0.4 km closure-heuristic bug) and
  per-leg airspace-crossing (currently point-in-polygon only).
- **Offline:** a saved/imported route survives a restart and is retrievable with
  **no network**. `[HELD]` — `RouteClaimsTest.offline`.
- **Resilient:** a malformed task import is rejected gracefully (null, no crash);
  a missing route degrades to null. `[HELD]` — `RouteClaimsTest.resilient`.

## The Claims Report (replaces the dashboard)

For each claim: `HELD` / `BROKEN` + a one-line evidence narrative, e.g.

```
K2 · offline   HELD    Bir Billing 45 km, network cut: 1,240 airspaces from cache, 0 misses
K2 · resilient HELD    airspace cache corrupted @ t+12m: airspace hid, map/route/peers OK, no crash
K5 · correct   BROKEN  peer rel-altitude off by 80 m vs. injected truth at t+3m
```

## First proof (proposed)

`K2 · offline` on the **Bir Billing replay** — cut the network, replay the track,
assert zero airspace cache misses + 0 network calls. One real, trustworthy,
emulator-free demonstration of the whole approach.
