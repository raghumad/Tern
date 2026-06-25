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
- **Correct (task corridor):** a task that crosses controlled (A/B/C) airspace is flagged by
  **per-leg segment intersection** — not just waypoint-in-polygon — so a leg flying *through*
  airspace between two outside waypoints is caught; names the airspaces crossed.
  `[HELD]` — `SpatialSafetyUtilsTest` (leg-crossing both-waypoints-outside, MultiPolygon, class
  filter). Surfaced in the task HUD ("AIRSPACE · crosses Geneva TMA").
- **Flight risk (whole-task synthesis):** the headline verdict folds every air-mass factor the
  engine reads (wind, gusts, gust factor, shear, convection/CAPE, lightning, visibility, precip)
  **+ airspace + daylight (ETA vs sun) + terrain (cloudbase-below-terrain)**, read at each
  waypoint's ETA — advisory and transparent (worst KNOWN factor named with value + where/when;
  missing forecast reported, never faked). Replaces the old convective-only storm alarm.
  `[HELD]` — `TaskFlightRiskClaimsTest` (no false alarm, wind escalates, convection surfaced,
  airspace folds in, worst-point wins, missing-data transparent, daylight-after-sunset,
  cloudbase-below-terrain). `weather/FlightRisk.kt`. ETA→forecast-clock shift fixes the
  true-epoch-vs-local-as-UTC sampling mismatch.
- **Timely:** awareness of airspace ahead fires *before* the boundary **in flight**. `[GAP]`
  Still **not implemented** — the live query is reactive (static 200 km horizon on centre
  change); no trajectory lookahead/prediction yet. (The *task-planning* crossing check above is
  done; this remaining gap is the in-flight predictive warning.)
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
- **Time basis is true epoch (K4 · correct):** Open-Meteo's `timezone=auto` wall-clock
  strings are parsed to genuine instants (`parseForecast` subtracts `utc_offset_seconds`),
  the site offset is carried on `WeatherForecast.utcOffsetSeconds` for display, and the sheets
  read it back as the launch's wall clock via `siteTimeZone(...)`. A true-epoch ETA now samples
  the right forecast hour (was off by the site offset — the "sunrise 23:29" class of bug), and
  `FlightRisk` dropped its `toForecastClock` shim. `[HELD]` — `WeatherTimeBasisClaimsTest`
  (parse → true instant · sun times shifted · formats back to site wall clock).
- **Thermal outlook (K4 · temporal climb strength):** "*when today* the thermals work and
  *how strong*" — a numeric **w\*** (Deardorff convective velocity scale) climb-rate per daylight
  hour from `shortwave_radiation` (→ sensible heat flux) + boundary-layer depth, plus the parcel
  thermal top and cumulus base, reported as the working window + peak. Withholds the number
  (rather than faking it) when a source omits the solar/depth inputs; qualitative strength
  survives. `[HELD]` — `ThermalForecastClaimsTest` (w* magnitude + monotonic in sun/depth · no
  sun → null · daylight-bounded window · peak number+strength+top · honest degradation).
  `weather/ThermalForecast.kt`, `ui/weather/ThermalOutlookCard.kt`.
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
- **Stability diagram:** the Skew-T plot. `[HELD]` Shipped — a real pressure-level
  sounding plot in the weather sheet (parcel ascent, cloudbase, thermal top), not a
  placeholder. `SkewTPlot` + `Sounding`/`SoundingTest`.

### K5 — Team (Mezulla)
- **Correct:** each peer's position / rel-altitude / heading / distance correct. `[ ]`
- **Timely:** updates live; staleness shown; off-screen buddies via edge markers. `[ ]`
- **Offline:** works over LoRa mesh with no internet at all. `[ ]`
- **Resilient:** peer dropout → ages out / marked stale; HUD + map never crash. `[ ]`

### K6 — Task / waypoints  *(JVM logic + on-device pilot-outcome)*

> **Honesty note (2026-06).** Most K6 claims below are **L0** (reducer-level): they
> assert `mapReducer` state, *not* the pilot journey on the live map — which is how
> long-press shipped dead and a delete crashed while the suite was green. Each claim
> is now graded **L0** (reducer) / **L1** (pilot outcome, automated) / **L2** (human
> rubric); "held" = L0 ∧ L1 ∧ L2. The full per-journey matrix + the plan to raise
> each to L1/L2 is in [claims-pilot-validation.md](claims-pilot-validation.md).
> L1 on the map needs a coordinate-driver (the GL surface never idles, so
> `ComposeTestRule` can't drive it).

- **Correct (geometry round-trip):** a task's waypoint positions, roles, cylinder
  radii **and `spotId` references** survive persistence (the latent "links die on
  restart" bug — `spotId`/`description` were never written). `[L0 HELD · L1 HELD]` —
  `TaskSpotModelClaimsTest` (round-trip + v0→snapshot fallback); `TaskClaimsTest`.
  Restart is genuinely pilot-honest (offline/persistence axis — no GL needed).
- **Reference model (edit once, flows everywhere):** task points reference library
  **Spots**; editing a spot flows to every task; features (role/cylinder/gates) are
  per-reference and **clearable**; PG spots can be pulled in as spots. `[L0 HELD ·
  L1 GAP]` — `TaskResolverClaimsTest` K10, `TaskBindClaimsTest` K11,
  `TaskFromLibraryClaimsTest` K9, `TaskMutationClaimsTest`, `TaskPgSpotClaimsTest`.
  Editing is now reachable straight from the panel tile (role / start gate / cylinder /
  rename) and PG spots are first-class in SEARCH + snap; L1 (pilot drives it end-to-end
  on the live map) still pending the coordinate-driver.
- **Create / move on the map:** `[L0 HELD · L1 PARTIAL]` — long-press create is wired
  (`onMapLongClick`, L1-verified) and now **ground-distance snaps** to an existing spot
  within ~150 m (`TaskReducers.snapToNearbySpot`; `TaskSnapClaimsTest` — near-drop references,
  forced add-from-map drop still snaps, open-space drop mints a USER spot) so a near-drop
  *references* it instead of stacking a near-duplicate. Reposition is
  **move-mode** (tap-select → "Move on Map" → tap), reusing the
  Start/Update/End/CancelWaypointDrag reducer machine; the commit's L1 is `@Ignore`d
  because a single GL tap can't be injected on-device (a long-press swipe can). No
  press-and-hold drag gesture by design — so the move-mode is a real feature, not a
  dishonest-green reducer claim.
- **Resilient (no crash, no dangling nav):** delete clears active/tagged nav
  (`NavStateCleanupClaimsTest` K14); and **no overlay can feed a non-finite value to
  the map** so rendering can't crash (`GeoJsonSafeTest`, P2). `[L0 HELD · L1 HELD]`
  for the render invariant (structural, no GL needed); the delete *journey* is L1 GAP.
- **Resilient (import):** a malformed task import is rejected gracefully (null, no
  crash). `[L0 HELD]` — `TaskClaimsTest`.
- **FAI-triangle detection:** a closed course is classified open-distance / flat / **FAI
  triangle** (each side ≥ 28% of perimeter → 2× points). Robust to the real shapes — the bare
  `[A,B,C,A]` *and* the 5-point comp `[SSS,TP1,TP2,TP3,GOAL]` (start snapped onto a corner,
  goal returns to start) — by collapsing coincident points to three corners. (Was hardcoded to
  exactly four waypoints, so every comp triangle read as open distance.) `[HELD]` —
  `TaskTriangleClaimsTest` (open · FAI · flat · 5-point comp · co-located start · quad-not-triangle).
- *To add:* per-leg airspace-crossing along the task corridor (point-in-polygon only).

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
  iff climbing, cloudbase-gap iff known & near (and out-ranking the others). `[HELD]` —
  `FlightStateClaimsTest` (HUD contextual cell picks the read that fits the phase).
  `FlightMetrics.hudContext`; wired into `VarioHud` (cloudbase case dormant until weather
  cloudbase is threaded into the deck).
- **Correct (vario units):** m/s ↔ ft/min conversion + formatting via `Units`. `[HELD]` — `FlightStateClaimsTest` (vario units).
- **Correct (auto-zoom):** circling tighter than gliding; within gliding, faster ground speed
  → wider; clamped to [min,max]. `[HELD]` — `FlightStateClaimsTest` (auto-zoom tighter circling than gliding, widens with speed).
- **Correct (keep-in-view):** the framing box includes own-position + next-WP + nearest buddy.
  `[HELD]` — `FlightStateClaimsTest` (framing box keeps own + next-WP + nearest buddy in view).
- **Resilient (source ladder):** a positioned vario fix → source XC_TRACER; on link loss →
  PHONE; a vario-only sample (no GPS lock) neither promotes nor flaps. `[HELD]` —
  `FlightDeckSourceClaimsTest` (promote on positioned fix · fall back on disconnect · no
  premature promote on connect · no demote on a later vario-only sample).
- **Resilient (device memory):** a remembered vario MAC persists and is offered next launch.
  `[GAP]`
- **Correct (altitude ref):** the readout switches MSL ↔ above-takeoff per the setting. `[HELD]` — `FlightStateClaimsTest` (height/altitude-reference).
- *On-device `[smoke]` (not JVM claims):* flight-track map layer, HUD + vario-bar render,
  rosette wind arrow, zoom→camera, connection pill.
  **Verified on-device 2026-06-14** (Power Armor 14 Pro) via the IGC *bench replay* — a bundled
  Bir Billing flight streamed through the live `$XCTRC → XcTracerParser → SensorFix` path (no
  hardware), driving: the climb-tinted `FlightTrackLayer` (green thermal coils + red glide line),
  the enriched `VarioHud` (climb + ø-avg, MSL + ▲height-above-takeoff, GS + L/D-when-gliding,
  live wind, source + battery), the live wind needle on the `Compass` rosette, and
  phase+speed `FlightCamera.autoZoom` camera-follow. Source: replay = `IgcReplaySource`
  (Settings → *Flight deck (bench replay)*). Still open: HUD stage selector, source ladder +
  device memory (below, JVM claims).

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
