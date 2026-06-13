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
