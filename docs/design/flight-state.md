# FlightState — the fused flight-state keystone

> **Status: DRAFT v0** — design for review before code. Edit freely. This is the
> structure the vario, the inferences, landing detection, the recorder, the map,
> and Spedmo all read from. Get it right once; everything bolts onto it.
>
> **Build started (2026-06).** The near-term sensor source is decided: an **XC Tracer
> vario over BLE** (it does inertial+baro fusion on-device and emits a `$XCTRC` stream of
> position, pressure-alt, fused climb, and attitude), ingested as a second peripheral beside
> the LoRa board — so the `EXTERNAL_BLE` source below is the *primary* near-term input, not a
> "later." Brains-first, like the weather arc. **Shipped:** the `wind` channel's estimator —
> `flight/WindEstimator.kt`, wind-from-drift while circling, claim-tested (K7 · 3 HELD) per
> open decision #4's plan (replay a real IGC flight, assert). **Next:** the `$XCTRC` parser
> and the baro+GPS Kalman `verticalSpeed` (the vario), then wire `FlightState` into Redux.
>
> **Validated on 197 real personal flights (2016–2019, Flytec).** ~101 had enough data to
> validate; on those the wind read held (airspeed always a paraglider's, two independent
> methods agreeing to ~11° median, jitter ~3°). The discovery worth baking into the design:
> **the wind window must adapt to the fix rate.** A thermal turn is ~12–20 s, so a coarse log
> rate under-samples the circle (Nyquist); Flytec logs at a *variable* 3–9 s/fix and needed a
> ~60 s window vs the 30 s that suited 1 Hz fixtures. The live `FusionEngine` therefore must
> derive the circling-wind window from the observed sample rate, and honestly report
> "fix rate too coarse" rather than silently withholding when the data simply can't resolve a
> circle (≳10 s/fix). The XC Tracer's regular high rate makes this a non-issue for live flight.

## Why this is the keystone

Everything downstream needs the same thing: an accurate, honest, *current* answer
to "where am I in 3D space, how am I moving, and how sure are we?"

```
Sensor sources (ranked)        FusionEngine            FlightState        consumers
  Mezulla (nRF+baro/IMU/GPS) ┐                       ┌─ verticalSpeed ──┐  vario (MVP)
  phone (baro/IMU/GPS)       ├─► fuse per-channel ──►├─ position/alt ───┼─ inferences (glide, 3D airspace, hazard)
  external BLE vario         ┘   + uncertainty       ├─ groundSpeed ────┼─ landing detection ─► Spedmo
  (FLARM/FANET later)                                ├─ heading/attitude┼─ flight recorder
                                                     └─ wind ───────────┘  the map (own-position, wind)
```

It serves claim **K7 — "I never have to wonder how fast I'm climbing, how high I
am, or where the lift is"** (see [../claims.md](../claims.md)) on all five axes —
and the two it leans hardest on are **Timely** (a laggy vario is useless) and
**Resilient** (a frozen vario mid-thermal is the nightmare).

## The core idea: every value is a *measurement*, not a number

A glass cockpit that lies is worse than one that's blank. So no field in
`FlightState` is a bare value — each carries where it came from, how stale it is,
and how sure we are. That single decision is what makes "never assert false
certainty" enforceable downstream.

```kotlin
/** A fused value plus its provenance and uncertainty. */
data class Measured<T>(
    val value: T,
    val uncertainty: Double,     // 1-sigma, in the value's own units (m, m/s, deg)
    val source: SensorSource,    // which input produced it (the degradation ladder)
    val ageMs: Long,             // how old the underlying sample is, at snapshot time
    val quality: Quality,        // FRESH | DEGRADED | STALE | UNAVAILABLE
)

enum class SensorSource { MEZULLA, PHONE, EXTERNAL_BLE, DERIVED, NONE }
enum class Quality      { FRESH, DEGRADED, STALE, UNAVAILABLE }
```

`source` is **per channel**, not global — baro can come from the Mezulla board
while GPS comes from the phone, and each falls down the ladder *independently*
when its input drops. That is the graceful-degradation principle made literal.

## The snapshot

Emitted by the FusionEngine as an immutable snapshot at the vario rate (target
**~25 Hz**, see open Q3). Grouped by what it answers.

```kotlin
data class FlightState(
    // ── time ──────────────────────────────────────────────
    val monotonicTimeMs: Long,                 // for deltas / rates (never wall-clock)
    val wallClockMs: Long,                      // UTC, for the recorder

    // ── position (3D) ─────────────────────────────────────
    val position: Measured<LatLon>,            // horizontal fix
    val altitude: Measured<Double>,            // fused MSL altitude (baro+GPS Kalman)
    val heightAboveTakeoff: Double,            // derived: altitude - takeoff datum

    // ── motion ────────────────────────────────────────────
    val verticalSpeed: Measured<Double>,       // THE VARIO (m/s, + up). Kalman-fused.
    val groundSpeed: Measured<Double>,         // horizontal speed (m/s)
    val track: Measured<Double>,               // course over ground (deg, GPS)
    val glideRatio: Double,                     // derived: groundSpeed / -verticalSpeed

    // ── heading & attitude (IMU — modelled now, MVP-deferred) ──
    val heading: Measured<Double>?,            // where the nose points (magnetometer)
    val attitude: Measured<Attitude>?,         // pitch/roll/yaw (AHRS)
    val gLoad: Measured<Double>?,              // acceleration magnitude

    // ── derived environment ───────────────────────────────
    val wind: Measured<Wind>?,                 // GPS-drift estimate while circling
)

data class LatLon(val lat: Double, val lon: Double)
data class Attitude(val pitchDeg: Double, val rollDeg: Double, val yawDeg: Double)
data class Wind(val speedMs: Double, val directionDeg: Double)
```

**Nullable vs UNAVAILABLE:** a channel that is *part of the model but not yet
implemented* (attitude, wind in v1) is `null`. A channel that *exists but has no
live source right now* is present with `quality = UNAVAILABLE`. Consumers treat
both as "don't use," but the distinction keeps the recorder honest about whether
a capability was off vs. failed.

## The vario fusion (the one that matters)

`verticalSpeed` is the product. Design: a 2-state Kalman filter
`[altitude, verticalSpeed]`:

- **Measurement:** barometric altitude (fast, smooth short-term).
- **Process input (optional):** vertical acceleration from the IMU → a
  responsive, accelerometer-aided vario (the difference between "good" and
  "great").
- **Absolute anchor (optional):** GPS altitude/vspeed, lightly weighted, to stop
  baro drift over long flights.

Degradation ladder for the vario, in order:
1. **baro + IMU + GPS** → best: responsive *and* drift-corrected. `FRESH`.
2. **baro + GPS** (no IMU) → smooth, slightly less responsive. `FRESH`.
3. **baro only** → fine for relative climb/sink. `FRESH` (it's what most varios are).
4. **GPS only** (no baro — e.g. Pixel 6–9) → laggy, low confidence. `DEGRADED`.
5. **none** → `UNAVAILABLE`; the vario shows "—" and goes silent. Never frozen.

Note the existing `docs/guides/development.md` already calls for "falling back to
Kalman-filtered GPS altitude" with no barometer — this formalises that.

## Consumer policy — how uncertainty gates behavior

The whole point of `Measured` is that downstream code **must** respect it:

| Consumer | Rule |
|---|---|
| **Vario (UI + audio)** | Show/sound if `quality >= DEGRADED`; if `UNAVAILABLE`, show "—", no audio. Never a stale beep. |
| **Glide-to-terrain / reachability** | Only assert "you'll clear it" if position+altitude+vspeed uncertainty are below threshold; otherwise **advisory** or **withhold**. Never assert false certainty. |
| **3D airspace prediction** | Same — a "TMA floor in 90 s" claim requires `FRESH` vspeed + altitude. |
| **Landing detection** | Requires `groundSpeed` + `altitude` `FRESH` and stable for 5 min (feeds Spedmo). |
| **Recorder** | Records *everything*, including `source`/`uncertainty`/`quality`, so replay is faithful and you can see which sensor was live. |
| **Map own-position** | Uses `position`; if `STALE`, shows the last-known with a staleness cue (don't snap to garbage). |

## MVP vs later

- **v1 (core vario suite):** `position`, `altitude`, `heightAboveTakeoff`,
  `verticalSpeed`, `groundSpeed`, `track`, `glideRatio` — sourced from phone
  GPS+baro, fused. `heading`/`attitude`/`gLoad`/`wind` are `null`.
- **Later:** IMU/AHRS (`attitude`, `gLoad`, accelerometer-aided vario), GPS-drift
  `wind`, then Mezulla-board sourcing, then the inference engine (glide, 3D
  airspace, hazard), then FLARM/FANET as another input.

The model is designed for the endgame; v1 only fills the channels it can.

## Where it lives (architecture)

The FusionEngine exposes a **hot `StateFlow<FlightState>`** — the vario/deck
subscribe directly for low latency. A **throttled projection** (≈10 Hz, matching
the existing 100 ms batch) updates Redux own-position for the map, so the map
keeps its single-source-of-truth without the 25 Hz firehose. The current
`ReduxLocationService` becomes *one input* to the fusion rather than the position
authority.

## Open decisions (your call)

1. **`Measured<T>` wrapper vs flat fields + a parallel uncertainty struct.** I
   recommend the wrapper — uniform provenance, and the consumer policy above is
   only enforceable if every value carries its quality. Agree?
2. **Altitude reference.** Lead with **height-above-takeoff** (no QNH calibration
   needed, always correct relative); offer QNH→MSL as an optional setting. OK, or
   do you want MSL-first?
3. **Emit rate + latency budget.** Propose **25 Hz** emit and a **< 100 ms**
   sensor-to-vario-display budget as the "Timely" claim's hard number. Right
   ballpark?
4. **First code target.** I'd build `FlightState` + `Measured` + the **baro+GPS
   Kalman vario**, and prove it with a **claim-driven unit test**: replay a real
   IGC climb (Bir Billing) through the fusion and assert the vario tracks the
   known climb rate within tolerance, and that dropping baro degrades (not
   breaks) it. That single test exercises K7 · Correct + Timely + Resilient.
