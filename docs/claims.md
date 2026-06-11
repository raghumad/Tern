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

### K2 — Airspace
- **Correct:** rendered polygons match source data for the viewport. `[ ]`
- **Timely:** awareness of airspace ahead fires *before* the boundary (≥ N s /
  ≥ M m out). `[ ]`
- **Frictionless:** only viewport-relevant airspace shown; declutters at density. `[ ]`
- **Offline:** airspace along the corridor served from cache; 0 network calls. `[ ]`
- **Resilient:** *stale/corrupt airspace → shows stale (indicated) or hides; map,
  route, peers, weather all keep working; no crash.* ← canonical degradation test `[ ]`

### K3 — Sites / landability (PG spots)
- **Frictionless:** nearby sites surface without a search. `[ ]`
- **Offline:** spots for the region cached. `[ ]`
- **Resilient:** missing/corrupt spot data → spots hidden, rest works. `[ ]`

### K4 — Weather / wind
- **Correct:** wind/forecast values match source for the position/time. `[ ]`
- **Offline:** last prefetched forecast available (stale-but-shown with age). `[ ]`
- **Resilient:** weather fetch fails → stale shown with age; never blocks the map. `[ ]`

### K5 — Team (Mezulla)
- **Correct:** each peer's position / rel-altitude / heading / distance correct. `[ ]`
- **Timely:** updates live; staleness shown; off-screen buddies via edge markers. `[ ]`
- **Offline:** works over LoRa mesh with no internet at all. `[ ]`
- **Resilient:** peer dropout → ages out / marked stale; HUD + map never crash. `[ ]`

### K6 — Route / task
- **Correct:** task geometry (cylinders, legs, FAI) and progress correct. `[ ]`
- **Offline:** task works offline. `[ ]`
- **Resilient:** bad task import rejected gracefully; app stays usable. `[ ]`

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
