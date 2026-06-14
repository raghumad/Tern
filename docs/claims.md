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
- **Soarable window (K4 · temporal):** "*when today* is this site flyable" — scans
  the day's hourly forecast with the site-aware verdict, bounded to daylight (Open-Meteo
  sun times, captured), and reports the contiguous GO window(s) (or marginal CAUTION
  runs), plus a structured daily digest (prevailing wind + on/off direction, gust,
  temp range, sky, precip). `[HELD]` — `WeatherClaimsTest` (soarable ×5: window ·
  daylight-bound · marginal fallback · digest · no-sun degradation). `weather/Soarable.kt`.
  *This is Tern's offline fallback for the Spedmo soarable forecast (backlog 3.10),
  tuned to the same factors so the two agree.*
- **Site-aware (K3×K4):** Flyability joins the launch geometry to the air mass — a
  launch only works in certain wind directions (PGE orientation octants), so a
  cross/behind wind is a no-go *for this launch* even when the air is otherwise GO
  ("the air's flyable, but not here today"); and cloudbase is read relative to the
  launch (height above launch + MSL), with a base at launch height flagged as
  in-cloud. `[HELD]` — `WeatherClaimsTest` (site ×4: wind-vs-hill · cloudbase-vs-launch
  · octant mapping · PGE→SiteContext parse). `SiteContext` + `assessFlyability(…, site)`;
  PGE fields threaded `PgSpotGeoJson → tap → dialog`. *Objective geometry only — stays
  off the subjective pilot-skill line.*
- **Thermal outlook:** lapse rate → expected climb rate / overdevelopment time.
  `[GAP]` Lapse rate is computed but never turned into a thermal forecast.
- **Source policy:** per-country best free model (HRRR/AROME/ICON-D2) + refresh
  cadence, with an independent fallback when Open-Meteo is down. `[HELD, logic]` —
  `WeatherClaimsTest` (source policy · fallback): `WeatherSourcePolicy` (country →
  model + TTL) and `FallbackWeatherAPI` (primary → secondary → cache, never throws).
- **Source wiring:** the policy is now applied to the *live* fetch — `OpenMeteoWeatherAPI`
  resolves the country offline (`OfflineGeocoder`, zero-I/O) and threads the specialized
  model into the URL (`&models=gfs_hrrr` over the US, AROME over France, …; `best_match`
  omits it); and the independent **MET Norway** secondary is live behind
  `FallbackWeatherAPI` in `WeatherMiddleware`, so an Open-Meteo outage degrades to a
  real surface forecast (CAPE/lightning null → no false hazard) rather than a blank.
  `[HELD]` — `WeatherClaimsTest` (source wiring · model into the live URL; MET Norway
  parses into a degraded surface forecast). `MetNorwayWeatherAPI` + `parseMetNorwayCompact`.
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

### K7 — Flight state / wind  *(JVM, via WindEstimator; IGC replay)*

> The fused flight-state keystone — see [design/flight-state.md](design/flight-state.md).
> First brain shipped: **wind from drift while circling**, the estimate the orientation
> dial / compass rosette wants and the airplane "groundspeed − airspeed" trick can't give a
> paraglider. Pure math over a `(time, lat, lon)` stream — phone GPS today, an XC Tracer
> tomorrow — so it's claim-tested offline against real flights.

- **Correct:** a glider circling at a known airspeed in a known wind has its wind
  recovered (speed + *from* direction) by the velocity-space circle fit. `[HELD]` —
  `FlightStateClaimsTest.correct` (synthetic drifting circle: 10 m/s air, 4 m/s @ 240°,
  recovered to ±0.6 m/s / ±8°).
- **Resilient (honesty):** a straight glide has no circle to fit, so the estimate is
  **withheld** rather than reporting noise as wind. `[HELD]` —
  `FlightStateClaimsTest.resilient` (straight cruise ⇒ null; phase = STRAIGHT).
- **Resilient (real flight):** a real Bir Billing thermalling track replayed from IGC
  yields stable, plausible reads — ≥95 % of confident reads carry a paraglider's airspeed
  (median ≈10.5 m/s), adjacent circles agree (median direction step <20°), non-circling
  stretches are withheld, and the wind is allowed to *veer* SW→NW over the flight (real, not
  asserted constant). `[HELD]` — `FlightStateClaimsTest` real-thermal replay.
- **Timely (vario):** `[GAP]` — fused vertical speed (baro+GPS Kalman, < 100 ms to display)
  is designed (flight-state.md) but not built. The XC Tracer hands us a fused climb directly;
  ingest is the next brain.
- **Correct (sensor ingest, parse):** the XC Tracer `$XCTRC` BLE sentence parses to a
  device-agnostic `SensorFix` (position, GPS alt, ground speed m/s, fused climb, pressure,
  battery, UTC) with NMEA-checksum validation, and feeds straight into the wind brain. `[HELD]`
  — `FlightStateClaimsTest.correct` (real checksummed sentence) + `.resilient` (bad checksum
  rejected; pre-GPS fix keeps the vario, null position). Field layout verified vs XCSoar's
  driver. Validated end-to-end on the device's own 184 logs (1 Hz): airspeed 9.2–12.1 m/s
  (matches the Ozone Alpina 3), circle-vs-min/max ~9°.
- **Correct (BLE reassembly):** the ~20-byte BLE notifications (which split sentences
  mid-field) reassemble into whole lines via `NmeaLineAssembler` and parse correctly. `[HELD]`
  — `FlightStateClaimsTest` feeds the *exact* fragment sequence captured from the real device
  (longitude `-104.953582` arriving as `…,-10` + `4.953582`) → one parseable fix.
- **Correct (sensor ingest, transport):** the BLE client is built — `XcTracerBleClient` scans
  for the vario, subscribes to **FFE0/FFE1** (Service `0000ffe0-…`, notify char `0000ffe1-…`,
  UUIDs confirmed by sniffing the real device — *not* Nordic UART), reassembles + parses, and
  emits a `Flow<SensorFix>` with auto-reconnect, as a second GATT peripheral beside the LoRa
  board. Android/GATT code, so not JVM-unit-tested; the pure pieces it composes are
  (`NmeaLineAssembler`, `XcTracerParser`).
- **Correct (live wiring):** the vario is wired end-to-end — a "Connect vario" shelf button
  toggles scanning; fixes flow `XcTracerBleClient → CirclingWindTracker → Redux` (`UpdateVarioFix`),
  and the deck shows a live vario/altitude/wind HUD. Per the source ladder, a positioned vario
  fix becomes the **own-position authority and powers down the phone GPS** (battery offload),
  falling back on disconnect. The streaming wind wrapper is claim-tested (`CirclingWindTracker`
  recovers wind from a fix-by-fix circle; a pre-GPS vario sample doesn't disturb it).
  **Verified on-device 2026-06-13** (Power Armor 14 Pro + the real XC Tracer Mini II): tapped
  Connect → scan → FFE1 subscribe → live `$XCTRC` streaming (ATT notifications) → the deck HUD
  showed live altitude (~4920 ft) updating in real time. The first-connect GATT-133 drop was
  self-healed by auto-reconnect. Still to verify in flight: the live **wind** read (needs
  circling) and the phone-GPS hand-off under real movement.

### K7 — Flight-deck UI build (claims queued — fix one-by-one)

> The board tracks these GAP→HELD as each lands; BROKEN ≠ 0 means a regression. Each is a
> pure-logic claim tested both synthetic-exact and via **IGC→`$XCTRC`→parser replay**
> (`IgcToXctrc`, HELD round-trip) so it exercises the live pipeline, not a shortcut. `[smoke]`
> items are Android/Compose, verified on-device, not on the JVM board.

- **Correct (flight track):** own-position fixes accumulate into a decimated, ring-buffered
  track (oldest dropped at the cap). `[HELD]` — `FlightStateClaimsTest` (flight track decimates and ring-buffers; real-flight trail via IGC replay).
- **Correct (track tint):** a track segment's colour maps from its climb (green ≥ +0.2, red
  ≤ −0.2, neutral between) — the thermal-map trail. `[HELD]` — `FlightStateClaimsTest` (track segment tint maps from climb, incl. gap-break).
- **Correct (glide ratio):** L/D = ground speed / sink; withheld when climbing or sink ≈ 0;
  clamped to a sane max. `[HELD]` — `FlightStateClaimsTest` (glide ratio).
- **Correct (thermal averager):** averaged climb over the configured window matches a known
  series. `[HELD]` — `FlightStateClaimsTest` (averager window + real-flight realism via IGC replay).
- **Correct (height-above-takeoff):** takeoff datum captured from the first fix; height =
  altitude − datum. `[HELD]` — `FlightStateClaimsTest` (height/altitude-reference).
- **Correct (HUD stage logic):** the cluster content selector — L/D shown iff gliding, ▲launch
  iff climbing, cloudbase-gap iff known & near. `[GAP]`
- **Correct (vario units):** m/s ↔ ft/min conversion + formatting via `Units`. `[HELD]` — `FlightStateClaimsTest` (vario units).
- **Correct (auto-zoom):** circling tighter than gliding; within gliding, faster ground speed
  → wider; clamped to [min,max]. `[GAP]`
- **Correct (keep-in-view):** the framing box includes own-position + next-WP + nearest buddy.
  `[GAP]`
- **Resilient (source ladder):** a positioned vario fix → source XC_TRACER; on link loss →
  PHONE (reducer). `[GAP]`
- **Resilient (device memory):** a remembered vario MAC persists and is offered next launch.
  `[GAP]`
- **Correct (altitude ref):** the readout switches MSL ↔ above-takeoff per the setting. `[HELD]` — `FlightStateClaimsTest` (height/altitude-reference).
- *On-device `[smoke]` (not JVM claims):* flight-track map layer, HUD + vario-bar render,
  rosette wind arrow, zoom→camera, connection pill.

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
