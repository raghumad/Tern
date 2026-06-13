# Flight-deck UI — scrub & re-envisioning

> **Status: design / exploration (2026-06).** Captured from a UI scrub of the
> map screen's right-edge shelf and the compass rosette. The *implementation*
> is deferred until we revisit UI interactions; this doc is the spine that the
> backlog points at.

## The core realization

Tern has **two contexts**, and almost the entire map-screen UI today was built
for the first one:

1. **On the ground** — planning, pre-flight, post-flight. Free hands, time to
   tap menus, explore the map, build routes, scrub weather. The right-edge
   shelf (Settings / Share / Route) and the SettingsSheet all live here.
2. **In the air** — the *flight deck*. Hands busy, one-glance reads, near-zero
   interaction. The map becomes a moving backdrop; the foreground wants
   **instruments** — altitude, vario/climb, ground speed, glide ratio,
   next-waypoint bearing + distance, airspace-ahead, wind, peers.

The flight deck isn't a new screen so much as a **mode switch** — auto-detected
from FlightState (grounded ↔ launched/flying) or a manual toggle — where the
planning chrome recedes and the instruments take the foreground.

The weather UI we just built is the first proof of this language: the
**orientation dial** (colored flyable arc + wind arrow + barbs) and the
Flyability cards are exactly the kind of glanceable instrument the deck needs.
The deck is "more of that," driven live by FlightState.

## Scrub: the right-edge shelf

`TernMapScreen` → a `CenterEnd` column of `DockButton`s (40 dp dark-glass circle):

| Element | Icon | Action | Notes |
|---|---|---|---|
| Settings | gear | SettingsSheet (units, style, layers, demo) | ground-only |
| Share | `⋮` MoreVert | ShareSheet | **icon mismatch** — overflow glyph labeled "Share" |
| Route | route glyph | RouteListScreen | ground (planning) |
| Mezulla view-mode | dynamic | cycles peer HUD mode + link state | flight-relevant |

Findings:
- **`AddWaypointButton`** exists, is a `Toast` stub, and is **never wired**.
  *(Deferred to the routes-UI pass — anything waypoint-related is handled there.)*
- **No recenter / "my location" button** — no manual way to recenter on the pilot.
- **Share icon** reads as "more", not "share".

## Scrub: the compass rosette

`MapControls.Compass` — a ring + red north-carat, top-right, rotates to the
camera bearing. Gated by `compassVisible` (always true; no toggle UI).

Original intent (Raghu): *"I built the compass because I wanted to show more
information there at a glance."* It never grew past a passive north indicator.

Bugs found & fixed during the scrub (commit `029e022`):
- MapLibre's **native compass** ornament was also on (TopEnd) → two compasses,
  the native one half-obscured. Disabled it (`OrnamentOptions(isCompassEnabled=false)`).
- Our compass pointed the **wrong north** — rotated `+bearing` instead of
  `−bearing`. Fixed.

Still open: the rosette is **not tappable** (no reset-to-north — standard on
every map app).

## Re-envisioning

### The compass rosette → the deck's primary instrument

This is the piece to grow into the at-a-glance hub it was meant to be. In
flight it should become a **track-up heading ring** carrying, at a glance:

- the pilot's **track / heading**,
- the **wind vector** (we now have surface + aloft wind),
- the **next-waypoint bearing** (when a route is active),
- optionally the site's flyable arc when near a known launch.

It should adopt the **orientation-dial visual language** (colored arc, wind
arrow + barbs) — the two should converge into one instrument, not two separate
wind widgets. Tap → reset-to-north (and/or cycle what it overlays).

### The shelf → mode-aware

- **Ground mode:** today's shelf, cleaned up (Settings / Route / Share).
- **Flight mode:** planning controls recede; the foreground becomes the deck
  (vario, altitude, speed, glide, next-WP, airspace-ahead). Mezulla view-mode
  stays (it's already an in-air HUD control). Route → "active route progress"
  (the `RoutePlanningHUD` / `RouteDetailPanel` already lean this way).

## Quick wins (whenever we revisit UI; small, safe)

- Compass **tap → reset-to-north**.
- Add a **recenter / my-location** button to the shelf.
- Fix the **Share icon** (share semantics, not overflow).
- *(Deferred to routes-UI: remove the dead `AddWaypointButton`.)*

## Dependencies

- The full deck (vario / altitude / glide / 3D position) depends on the parked
  **FlightState** keystone — see [flight-state.md](flight-state.md) — sensor
  fusion (GPS + IMU + baro) into a `Measured<T>` per channel.
- The wind/orientation half is **already shipped** (weather deck), so the
  rosette re-envisioning can start before full FlightState lands.

---

# In-flight information inventory (2026-06)

What the pilot wants in the air, by glance-frequency tier. Source, and status:
✅ live now · ◐ derivable now · ○ future. Most of Tier 1–2 is already live or one
small step away; Tier 3 is the future FlightState / route / DEM work.

**Tier 1 — constant glance (core instruments)**
| Info | Source | Status |
|---|---|---|
| Vario — instant climb/sink | XC Tracer | ✅ |
| Vario — averaged (thermal averager ~20–30 s) | derived | ◐ |
| Altitude — MSL & height-above-takeoff | XC Tracer baro | ✅ |
| Own position on map | XC Tracer/phone | ✅ |
| Flight track (climb-tinted breadcrumb) | derived | ◐ |

**Tier 2 — frequent (tactical)**
| Info | Source | Status |
|---|---|---|
| Wind — speed + direction (live circling-drift) | WindEstimator | ✅ |
| Ground speed | XC Tracer | ✅ |
| Glide ratio (current L/D) | derived | ◐ |
| Track / heading | XC Tracer | ✅ |
| Buddies — position, rel-altitude, climb, distance | Mezulla | ✅ |
| Cloudbase vs current altitude ("room to climb") | forecast + alt | ◐ |
| Airspace — lateral proximity | cache | ✅ |
| Next-WP — bearing, distance, arrival height | route + glide | ○ |

**Tier 3 — occasional / alert-driven**
| Info | Source | Status |
|---|---|---|
| Airspace ahead / time-to-airspace | derived | ○ (K2 gap) |
| Vertical airspace proximity ("180 m below CTR floor") | alt + airspace | ○ |
| Nearest landable / LZ + glide to it | PG spots + glide | ○ |
| Terrain clearance / glide-to-terrain | DEM + alt | ○ (advisory only) |
| Storm / precip risk ahead | forecast | ◐ |
| Thermal core assist | IMU/GPS | ○ |
| SOS alert from a buddy | Mezulla | ✅ |
| Sunset / daylight remaining | derived | ◐ |

**Always-on status (small, edge)**: fix quality + source (XC Tracer vs phone) ✅,
XC Tracer battery + link ✅, Mezulla LoRa link ✅, phone battery ✅,
recording/logging ○, clock + flight duration ◐.

## Map zoom (in flight)

Zoom follows *what you're doing*, using inputs we already have — circling detection
(`WindEstimator.classifyPhase`) and ground speed (`$XCTRC`):

- **Thermalling (circling)** → zoom **in**: your turn, immediate terrain, any buddy
  sharing the thermal.
- **Gliding (straight)** → zoom **out**: track ahead, next WP, airspace approaching.
- **Speed scales within each phase** (faster glide = wider look-ahead).
- **Keep-in-view**: always own-position; next WP if a route is active; nearest buddy
  (else the off-screen edge chip covers it).
- **Manual pinch wins**; a recenter/auto button resumes auto-zoom.
- **Orientation**: offer **track-up** in flight, rosette showing N.

## Mezulla buddies in the deck

First-class Tier-2 layer, mostly built. In-flight value is **tactical thermal-finding**:
a buddy climbing well two ridges over *is* the thermal marker. Contributes: map markers
(SAFETY/CLIMB/TACTICAL view-mode line), off-screen edge chips, a feed into zoom, and SOS.
Future polish: highlight a buddy in strong lift → the peer layer becomes a shared thermal map.

# Scenario-driven mockups (real flight data)

Layouts validated against **real numbers** extracted from the bundled IGC fixtures
(Bir Billing — Richard; Aravis team), so the deck is designed against actual flight,
not invented values. Layout = "Map-hero A": vario bar (left edge), compass+wind
(top-right), shelf (right), HUD cluster (bottom-left), flight track on the map.

### Stage 1 — Thermalling (Bir Billing, t+3:30, **+4.2 m/s avg @ 4496 m**, 2164 m above launch)
```
┌────────────────────────────────┐
│ 11:42 · flt 3:30 ··········🔋78%│
│┃+5                     ╭──────╮ │  wind 84°·3kt (light)
│┃█  vario bar           │  ▲N  │ │
│┃█  pegged green        │   ↘  │ │
│┃▔                      ╰──────╯ │
│┃   ◜╮  tight circles      ⚙ ⋮  │  MAP zoomed IN
│┃   ╰─╯ (track green)      ⤳ ≋● │  (circling detected)
│┃       ◉ you                ◉  │
│╭─────────────────╮             │
││ ▲ +4.2 m/s      │             │  ← avg climb dominates
││ ALT 4496 m      │             │
││  ▲launch 2164 m │             │
││ ◉ XC Tracer 🔋24%            │
│╰─────────────────╯             │
└────────────────────────────────┘
```

### Stage 2 — On glide (Bir Billing, t+1:37, **53 km/h, L/D 17.6**, sink −0.8, 3624 m)
```
┌────────────────────────────────┐
│ 11:48 · flt 3:36 ··········🔋77%│
│┃+3                     ╭──────╮ │  wind 229°·3kt (SW)
│┃▔  vario bar           │  ▲N  │ │
│┃▂  low (red)           │  ↗   │ │
│┃█                      ╰──────╯ │
│┃  ╱ long straight track   ⚙ ⋮  │  MAP zoomed OUT
│┃ ╱  (track red on glide)  ⤳ ≋● │  (look-ahead)
│┃◉you ───→                   ◉  │
│╭─────────────────╮             │
││ ▼ -0.8 m/s      │             │
││ ALT 3624 m      │             │
││ GS 53 · L/D 17.6│             │  ← glide stats appear
││ ◉ XC Tracer 🔋24%            │
│╰─────────────────╯             │
└────────────────────────────────┘
```

### Stage 3 — Flying with buddies (Aravis, closest-cluster moment)
Real snapshot from CBE's deck: me **+1.9 @ 2311 m**; COR 549 m away, 129 m below, +1.2;
TONIO 619 m away, 125 m below, +0.8; LMA 24 km off, +618 m, **climbing +3.7** (off-screen).
```
┌────────────────────────────────┐
│ 14:05 · flt 1:10 ··········🔋80%│
│[▲ LMA +3.7  24km NE]──╮ ╭──────╮│ ← off-screen buddy chip
│┃+3                    ╯ │  ▲N  ││   (found strong lift)
│┃█                      │  ↗   ││  wind 239°
│┃▔  COR◉+1.2          ╰──────╯│
│┃    -129m   TONIO◉+0.8   ⚙ ⋮  │  buddies, CLIMB mode
│┃            -125m        ⤳ ≋● │  (climb + rel-alt)
│┃       ◉ you +1.9        ◉ ←view│
│╭─────────────────╮             │
││ ▲ +1.9 m/s      │             │
││ ALT 2311 m      │             │
││ ◉ XC Tracer 🔋24%            │
│╰─────────────────╯             │
└────────────────────────────────┘
```

### Stage 4 — High, near cloudbase (Bir Billing peak, **5011 m**, 2679 m above launch)
```
┌────────────────────────────────┐
│ 11:45 · flt 3:33 ··········🔋78%│
│┃+5                     ╭──────╮ │
│┃█  vario easing        │  ▲N  │ │  wind 75°·5kt
│┃▔  (+0.5, topping out) │   ↘  │ │  (stronger up high)
│┃                       ╰──────╯ │
│┃  ☁ CLOUDBASE ~5050 m — 40 m    │  ← Tier-3 cue
│┃  ◉ you  [near base, ease off]  │
│╭─────────────────╮             │
││ ▲ +0.5 m/s      │             │
││ ALT 5011 m      │             │
││  ▲launch 2679 m │             │
││ ◉ XC Tracer 🔋24%   ☁ -40m   │
│╰─────────────────╯             │
└────────────────────────────────┘
```

**What the scenarios prove for layout:**
- The HUD cluster's lines are **stage-adaptive** — L/D appears on glide, ▲launch on
  climb, ☁ gap near base — so the bottom-left stays terse, not cluttered.
- **Zoom is phase-driven** (in while circling, out on glide) and needs no manual input.
- **Buddies** ride the map + off-screen chips; the strong-lift buddy is the headline
  tactical cue.
- Everything in Stages 1–3 is **live or derivable today**; only the cloudbase gap
  (Stage 4) and route/airspace-ahead are future.
