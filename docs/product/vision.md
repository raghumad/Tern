# Tern — product vision & scope

> The decision filter for "should we build X?" If a feature doesn't serve the
> universal pilot, it's a layer — not the core.

## The one line

**Tern removes the unknowns of flying — *here, now, and soon* — offline, for every
pilot.**

A "route" is just how far ahead you're looking. Same foundation, scaling up.

## Who it's for

| Segment | Size | Flies | Primary need |
|---|---|---|---|
| **Recreational / local** | the majority | their site — sled rides, soaring, small thermals | *is it flyable here, what's the airspace, where do I land, where are my buddies* |
| **XC** | growing minority | a line they plan themselves | the above, **along a route** — lift, glide, airspace ahead, land-out |
| **Competition** | a small delta | an *imposed* task (XCTSK + gates) | the above, on a **precise task** — timing, cylinders, optimal line |

We build for **everyone**. Competition pilots are a small slice; building the
product *around* tasks/gates would alienate the 95% who never fly one.

## The unifying idea: route is a spectrum, not a mode

```
a point     →     a line you draw     →     a line imposed on you + gates
(my site)         (XC free flight)          (a competition task)
recreational          XC pilot                   comp pilot
```

Same machinery at increasing structure. There is **no "comp mode" vs "free mode"** —
there is **one route abstraction** that is as simple as a site or as rich as an
XCTSK, and the *same* weather / airspace / sites / offline foundation hangs off it
either way.

## Core vs. layered (the priority order)

1. **Universal local picture** *(serves 100%)* — flyability + airspace + sites +
   buddies **at/around the pilot**, **offline**. This is the product. It maps
   directly to the claims matrix (K1–K5 are the pilot's *knowns*, not tasks).
2. **Route planning** *(XC minority)* — the self-drawn line and weather/airspace
   *along* it (per-leg wind, wind-corrected ETA, hazard timing).
3. **Task import** *(comp delta)* — XCTSK / QR + start gates. Thin, last.

Everything collapses gracefully: weather-along-a-route becomes "weather at my site
for the next few hours" when the pilot isn't going anywhere, and expands to
per-leg/task when they are. **Same code, scaling down and up.**

## Non-negotiables (the principles these ride on)

- **Offline-first.** The pilot's last reliable internet is *before* launch
  (briefing / accommodation). Everything needed in the air is fetched and cached
  while online; nothing in flight depends on connectivity.
- **Never breaks, only degrades.** A missing sensor, a stale cache, a downed
  weather provider — the affected feature degrades; the app stays usable. A crash
  mid-flight is the worst unknown of all.
- **Show the why.** "Removing the unknown" means surfacing the reasoning (wind,
  hazards, airspace) and letting the pilot decide — not a black-box verdict.

## What this means in practice

- Lead with the **here/now/soon** experience, not a planning canvas.
- Build features so they **work at a point first**, then scale along a line.
- Treat competition (XCTSK, gates) as a **thin specialization** bolted on last —
  never the thing the everyday pilot has to step around.
