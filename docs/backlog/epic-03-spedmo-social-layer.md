# Epic 03: Spedmo social layer — pilots feel less alone

> This file describes the **end goal** — what Spedmo integration looks
> like once complete. Active work toward Epic 01 (buddy mesh) takes
> priority. This epic starts after Epic 01 MVP ships.

## Why this matters

Paragliding is solitary in the air and mostly solitary on the ground
too. A pilot drives to launch wondering "will anyone else be there?",
flies wondering "did anyone see that?", lands wondering "did my flight
compare to others?", and goes home with no record beyond a GPS file
they'll forget to upload.

Spedmo (spedmo.com — Spring + Postgres, REST API, ~1000-site
database, IGC storage, live tracking) is a friend's existing platform
that already solves most of this for its own users. Tern can plug
into it and expose the value to every Tern pilot. The result: pilots
who feel part of a flying community even when they fly alone.

Tern stays **fully offline-first**. Spedmo is purely additive
enrichment. If Spedmo is unreachable, the pilot loses nothing they
had before — they just don't get the bonus.

## What done looks like

- A pilot opens Tern at home the night before flying and sees who's
  been flying their planned site this week, with recent IGC tracks
  on the map.
- A pilot at launch glances at the screen and sees other pilots
  currently in the air nearby, plus a short note from someone who
  landed an hour ago: "lift starting at 11, cloudbase 2800m."
- A pilot in flight sees a faded marker for a buddy 30km away on a
  different ridge — out of LoRa range, fed via Spedmo livetracks
  over cellular. Differentiated visually from mesh peers so the
  pilot knows it may be stale.
- The pilot lands and ten minutes later their IGC has been uploaded,
  XC-scored, and a "Landed safe at 16:32" notification has gone to
  their partner who was watching the live link.
- The pilot has not done anything manual. No buttons pressed, no
  uploads triggered, no logins re-entered.

## Out of scope (other epics or never)

- Real-time mesh communication in flight — that's Mezulla (Epic 01).
  Spedmo is for cellular-relayed enrichment, not the primary path.
- Replacing offline maps, airspace, or sites — Tern keeps owning
  these locally. Spedmo provides extras, not substitutes.
- Building a Tern-specific social network from scratch.
- Cellular-required features. Anything that breaks when the phone
  loses signal is wrong for Tern.

## Stories

### Story 3.1: Spedmo account link (one-time OAuth)
Status: todo

A pilot links their Spedmo account once via OAuth (Facebook, Google,
or Apple — Spedmo already supports all three). Tern caches the
returned UUID + API access key locally. After this, Tern uses the
cached key for all subsequent requests — no re-login needed even
when offline.

What done looks like:
- Settings has a "Link Spedmo account" option.
- OAuth completes via system browser, redirects back to Tern.
- API key + UUID persisted in Android Keystore (encrypted at rest).
- Linked state survives app restart and offline launch.

### Story 3.2: Silent site enrichment
Status: todo

When the pilot pans the map near any Spedmo-known site (~1000+),
Tern silently downloads the site's metadata (description, photos,
recent activity, watcher count, weather station ID) and caches it
offline. The pilot sees enriched site cards without ever asking.

What done looks like:
- Pre-fetch triggers on map idle within X km of an unknown site.
- Cached data persists across app restarts and offline launches.
- Cache invalidates after N days (configurable).
- Pilot sees site description, photos, recent flight count on the
  site marker tap.
- If offline, the pilot sees the cached version — never a spinner
  or error.

### Story 3.3: Who's flying here right now
Status: todo

Pre-flight screen shows other pilots currently active in livetracks
within ~50km of the pilot's current location (or the site they're
viewing). Refreshes every few minutes when foregrounded.

What done looks like:
- Card: "3 pilots in the air nearby — Tomas (XC, 47km), Sarah (12km
  from launch), …" with tap-to-see-on-map.
- Silent when offline; shows last known when reconnecting.
- Respects privacy: only shows pilots who have opted into public
  livetracks on Spedmo.

### Story 3.4: Cellular-relayed peer markers (out of mesh range)
Status: todo

In flight, when a pilot is paired with a Mezulla board AND has
cellular signal, Tern fetches livetracks for known buddies and
renders them on the map as faded peer markers — visually distinct
from Mezulla mesh peers (who are real-time, confirmed close-range)
so the pilot knows these may be stale or imprecise.

What done looks like:
- Faded marker style ("via cell" badge) clearly separates from
  Mezulla peers.
- Refresh interval is conservative (every 60–120s) to limit cell
  usage at altitude.
- Auto-pauses when cell signal drops.
- Disappears entirely when the pilot has no cell — no error UI.

### Story 3.5: Auto IGC upload after landing
Status: todo

Tern detects landing (ground speed near zero + baro stable for 5
minutes) and queues the flight IGC for upload to Spedmo. Upload
happens whenever the phone next has cellular signal — could be
immediately after landing, could be on the drive home, could be
the next morning. The pilot does nothing.

What done looks like:
- Landing detection works reliably across launch sites and flight
  styles (sled ride, XC, top-to-bottom hike-and-fly).
- Queued uploads survive app restarts and reboots.
- Upload reports "queued" / "uploaded" / "scored" states in the
  pilot's flight log within the app.
- Failed uploads retry with exponential backoff, never block.

### Story 3.6: "Landed safe" notification to ground crew
Status: todo

When landing is detected, Tern pushes a "Landed safe at HH:MM,
location: …" message to Spedmo. Spedmo's notification system
forwards it to whoever the pilot designated as their watchers
(spouse, retrieve driver, club). Removes the single biggest source
of unnecessary worry and false SAR calls.

What done looks like:
- One-tap setup: "Tell my partner when I land safe" → enter their
  contact, link is created.
- Notification fires within 30s of landing detection when cell is
  available.
- Falls back to deferred send when cell returns.
- Pilot can manually re-fire ("I'm safe but late") from a glove-
  friendly post-landing screen.

### Story 3.7: SOS forwarding (defense in depth)
Status: todo

When the pilot fires SOS in flight (Epic 01 Story 1.4), Tern
broadcasts it over the mesh as the primary path AND simultaneously
pushes to Spedmo if cellular is available. Spedmo alerts the
pilot's watchers and (optionally, future) triggers automated SAR
notification.

What done looks like:
- Mesh SOS works without Spedmo (offline is the primary).
- When cellular is up, Spedmo is also notified within seconds.
- Notification includes last known position, altitude, time.
- No way for Spedmo dependency to weaken the mesh path.

### Story 3.8: Pilot reports — crowdsourced conditions
Status: todo

A pilot who has landed can tap "Post site report" → choose from
quick options (wind strength, thermal quality, hazard observed) +
optional free text. Posted to Spedmo, surfaced on the site card
for other Tern pilots viewing the same site later that day.

What done looks like:
- Post-landing screen surfaces the option with one tap.
- Reports decay (auto-hide after 12h) — fresh conditions matter.
- Other pilots see reports as soft annotations on the site marker.
- Posting works offline (queues + sends when cell returns).

### Story 3.9: Spedmo clubs → Tern buddy list
Status: todo

Tern reads the pilot's Spedmo club memberships and treats those
members as the default "buddy list" — they show up brighter in the
map's peer view, get "buddy landed safe" notifications routed to
them, and are the default scope for the social features above
(who's here, who's flying).

What done looks like:
- Tern shows "My clubs: Annecy Flying Club, Aravis XC" in Settings.
- Mesh peers who are also club members get a different visual
  treatment (filled callsign pill vs outline).
- "Who's flying here" defaults to club-filtered before public.

### Story 3.10: Soarable forecast from Spedmo (don't fork the algorithm)
Status: later · waiting on Spedmo to expose a partner API

Spedmo's site forecast card (the green "Soarable 08:00–09:00" window,
"on direction for Boulder", the temp/wind/direction chart, the plain
daily digest) is exactly the read Tern wants on a PG-spot tap. Spedmo
already computes it and already serializes it — so **Tern asks Spedmo
for the answer rather than re-implementing it.** One source of truth
means a pilot never sees "Tern: NO-GO" next to "Spedmo: soarable."

The model (read from Spedmo source, for reference only — we won't copy
it): an hourly **flyable probability** = windSpeed × windDirection ×
rain × cloud(low/mid/high) × time-of-day(daylight) × forecast-age,
with a good/marginal direction split (1.0 / 0.8) against the site's
16-point `windDirection` / `marginalWindDirection`. The "soarable
window" is the best contiguous run of that probability, daylight-bound.

Endpoints that already exist (currently session AJAX, POST form params):
- `POST /json/getForecast.pg?id=<spedmoSiteId>` → multi-day forecast
  with the soarable window + on-direction verdict + daily digest
  (`ForecastDaySerializer`). Works anonymously.
- `POST /json/getHourlyForecast.pg?id=<spedmoSiteId>` → hourly series +
  the flyable-probability values (the green-shading intensity).

Architecture — **same cache+fallback pattern as the weather provider**
(`FallbackWeatherAPI` / `WeatherSourcePolicy`):

```
SpedmoFlyabilityProvider  (authoritative · online · prefetched + cached)
        ↓ degrade when offline / stale / site unmapped
TernLocalFlyability       (already built — assessFlyability/assessOutlook
                           from Open-Meteo + MET Norway)
```

Online (home / any signal) we pull and cache Spedmo's soarable forecast
for the pilot's sites; offline in the field we serve the cache, and if
it's absent/stale we degrade to Tern's own Flyability. Never breaks,
only degrades (P1/P2). The local Flyability we built is the offline
safety net — and because we now know Spedmo's model, we tune the
fallback to *agree* with it, so the degradation is seamless.

What done looks like:
- A PG-spot tap shows the soarable window + on-direction verdict +
  24h chart with soarable shading + a plain daily digest, sourced from
  Spedmo when reachable.
- Offline / unmapped site → the same screen, computed locally, with a
  subtle "local estimate" indicator (vs "Spedmo").
- Soarable forecast is prefetched + cached per site like airspaces/spots.

Needed from Spedmo (Raghu to arrange — friend's platform):
- A stable, **authenticated partner API** endpoint (API key, versioned
  JSON, rate limits) rather than the internal session AJAX.
- A **site-id resolver**: `getForecast` keys off a *Spedmo* site id;
  Tern keys off PGE / lat-lng. Does Spedmo already store the PGE id, or
  do we resolve by location/name?

Depends on: Tern-local Flyability (the *now* verdict is done —
site-aware wind-vs-orientation + cloudbase + next-worsening outlook).
Still to build for the offline fallback: the **soarable-window scan**
(walk the day's hourly forecast → flyable intervals, daylight-bound, a
plain digest), tuned to agree with Spedmo's probability model. Relates
to the prefetch/cache machinery (Story 3.2).

## Open questions

- **OAuth flow in a paragliding app.** OAuth requires a browser
  round-trip — annoying mid-trip. Solve with one-time setup at
  home, then cache forever. If the token ever expires mid-trip,
  re-auth must wait until cell + browser available.
- **Privacy defaults.** Live tracking is opt-in only. Default is
  off, including auto-IGC-upload, until the pilot explicitly
  enables. Tern should never surprise a pilot by sharing
  something.
- **Rate limits.** Spedmo has no documented rate limits in code,
  but Tern should be a good citizen: pre-fetch on map idle, not
  on every camera move; batch livetrack pulls per area, not per
  pilot.
- **Site report quality.** Crowdsourced reports degrade fast if
  spammy. Limit one report per pilot per site per 4h, decay after
  12h, allow pilots to flag inaccurate reports.

## Related

- `project_tern_safety_stack` — Spedmo is the "cellular when
  available" layer above LoRa mesh.
- `project_tern_offline_first` — every Spedmo feature must
  degrade silently when cell is gone.
- `project_tern_silent_acquisition` — Spedmo data follows the same
  silent-cache pattern as countries, airspaces, PG spots.
- Spedmo source: `/home/raghu/src/paragliding/` (private — friend
  granted access).
