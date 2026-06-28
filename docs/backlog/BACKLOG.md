# Tern Backlog — single source of truth

This is the **one** place Tern's plan and status live. It used to be ~13
separate files (epics, current-focus, known-issues, audits, references); work
shipped on branches kept failing to propagate back, so the backlog drifted out
of sync with reality (e.g. Spedmo's offline fallback was built in the weather
arc but epic-03 still said "to build"). Consolidating into one file makes a
scrub a single read and keeps everything visible.

It lives in the repo on purpose — travels with the code, easy to search, no
GitHub Issues / Jira dependency.

## How this works

- **Epics** are end-user features — what a pilot can do, see, or feel. Not
  subsystems. Plumbing is a *story inside* an epic, never an epic.
- **Stories** are shippable slices of an epic.
- **Priorities:** `now` (active / up next) · `soon` (queued) · `later` (valuable,
  unscheduled — includes anything needing pilot behaviour change or new hardware).
- **Status legend (used everywhere below):**
  - ✅ **done** — shipped and verified
  - 🟡 **partial** — meaningfully underway or some sub-parts done
  - ⬜ **todo** — not started
  - ⏸ **parked/deferred** — paused on purpose (reason noted inline)
- **Mission check:** every epic must answer *what unknown does this convert into
  a known for the pilot?* If it can't, it doesn't belong.
- **Starting a new epic:** add a new `## Epic NN — …` section here. **Don't**
  create a new file — that's how the backlog scattered last time.
- **Current focus** = the bare-minimum slice in flight right now (one at a time);
  it points at whichever epic it serves. History of focus changes is in `git log`.
- Plain English. No "As a pilot, I want…", no story points, no sprint ceremony.

---

## 📊 Status dashboard

The scrub surface — skim this, drill into the section for detail.

### Epics

| Epic | Priority | Status | One-line |
|---|---|---|---|
| **01 — Buddy mesh + SOS** | now | 🟡 | Peers-on-map half ✅ on hardware; SOS half (1.4–1.7) ⬜ |
| **02 — Traffic awareness** | later | ⏸ | FANET/FLARM/ADS-L. **Shelved until V2 dual-radio hardware** (2026-06-27) |
| **03 — Spedmo social layer** | later | 🟡 | Offline Flyability fallback ✅; **Spedmo partner API + club model mostly already built** (IGC upload, livetrack push, club-scoped livetrack all exist); Tern-side wiring ⬜ |
| **04 — First-time onboarding** | soon | 🟡 | 7-step brochure. Region-from-GPS + release build started |
| **05 — Flight recording, logbook & export** | now | 🟡 | Launch→deck ✅; recorder+IGC+crash-survival+signing + logbook UI + **Spedmo auto-upload** built & wired (on-map replay / hw-signing / on-device upload verify ⬜) |
| **Tasks & waypoints** | now | ✅ | Task surface is pilot-grade; small polish items remain |
| **Firmware workstreams** | mixed | 🟡 | Stage 1/2 ✅; upstream cleanup + traffic firmware ⬜ |
| **Infrastructure & quality** | soon | 🟡 | Reactive overlays ✅ (soak gate ⬜); S5 one-liner ⬜ |
| **In-flight task interactions** | now | ✅ | Phases 0/1/2 built; final-glide (Phase 3) ⏸ |

### Story-level snapshot

**Epic 01:** 1.1 pair ✅ · 1.2 TX ✅ · 1.3 peers-on-map ✅ · 1.4 SOS ⬜ · 1.5 OLED status ⬜ · 1.6 OLED SOS ⬜ · 1.7 graceful degradation ⬜
**Epic 02:** 2.1–2.4 app (CPA/render/audio) ⬜ · 2.5–2.9 upstream broadcast ⬜ · 2.10–2.13 fork gap-scan ⬜
**Epic 03:** 3.1 OAuth ⬜ · 3.2 enrichment ⬜ · 3.3 who's-flying ⬜ · 3.4 cell-relayed peers ⬜ · 3.5 auto-IGC ⬜ · 3.6 landed-safe ⬜ · 3.7 SOS-forward ⬜ · 3.8 reports ⬜ · 3.9 clubs→buddies ⬜ · 3.10 soarable (offline ✅ / source ⬜)
**Epic 04:** 4.1 pre-flashed ⬜ · 4.2 region-from-GPS 🟡 · 4.3 Play Store 🟡 · 4.4 pairing robustness 🟡 · 4.5 no-PIN popup ⬜ · 4.6 concurrent pair ⬜ · 4.7 OTA ⬜ · 4.8 status-at-a-glance 🟡 · 4.9 web flasher ⬜
**Epic 05:** 5.1 launch→deck ✅ · 5.2 recorder + IGC export 🟡 (core+wiring built & tested; hw-signing + device-verify ⬜) · 5.3 local logbook 🟡 (store+stats+UI built; on-map replay + device-verify ⬜) · 5.4 Spedmo upload 🟡 (Tern side built; on-device verify ⬜ → 03 3.5/3.6) · 5.5 deck instrument hardening 🟡

---

## 🎯 Current focus

> **Stabilization is effectively complete and the buddy mesh is shipped &
> hardware-validated. The next focus is OPEN — to be chosen (see candidates at
> the end of this section).**

### What's done (the ground we stand on)

**Peers on the map, on real hardware.** End-to-end GPS → phone → BLE → board →
LoRa → board → phone → map, verified across three real flights via one unified
`MezullaPeerCycleTest` harness:

| Scenario | Pilots | Status |
|---|---|---|
| Aravis (France) | 4 | ✅ |
| Edith's Gap (USA) | 2 | ✅ |
| Bir Billing (Himalayas) | 3 | ✅ |

Delivered along the way: persistent self-healing BLE link with auto-reconnect
(PR #20) + reliability suite; Meshtastic ToRadio/FromRadio codec; the full peer
HUD; off-screen buddy indicators; `VirtualPeerInjector` + `MEZULLA_TEST_BUILD`.

**Buddy-flying hardened on two real phones (2026-06).** Two-pilot LoRa chain
fully fixed and verified (each phone sees only the other, live): packet-id,
precision-strip, team race, GATT queue, replay/timeless filter, hw_model filter,
reversible eviction. Board rename + display config from Settings (Meshtastic
`set_owner` field 32; persists across reset; propagates to buddies via a
push-on-change NodeInfo burst). The peer HUD was reworked into **one always-on,
glanceable marker** (view-modes removed; proximity/density declutter; bold; shown
at flight zoom) and validated with a synchronized two-device Bir Billing replay
over real LoRa.

**Release build is shippable (2026-06).** Signed + R8-shrunk (46→31 MB),
obfuscation off on purpose (Jackson maps DTOs by name), all demo/replay/test
surfaces `BuildConfig.DEBUG`-gated and verified absent in release. See
[Publishing checklist](#publishing-checklist).

**Stabilization (the "make green trustworthy, then fix the real breakage" arc)
is done:**
- Phase 0 structural cleanup ✅ (dead OSMDroid render code deleted, `java/`→
  `kotlin/` source move, god-files split, `utils/` reorganised).
- Phase 1 trustworthy harness ✅ — auto-download already gated; the gesture
  harness was rebuilt as `androidTest/.../map/MapDriver.kt` (UiAutomator raw
  touches + the activity-scoped `MapStore` as oracle), first honest pilot-outcome
  claims green (`TaskMapClaimsTest`: create / tap-select / delete-no-crash). See
  [../claims-pilot-validation.md](../claims-pilot-validation.md).
- Phase 2 real-workflow fixes ✅ — route clusters, settings-units, **the K4
  weather/Flyability deck** (site-aware Flyability, soarable-window scan, source
  policy, surface+aloft wind, thermal outlook w*, Skew-T, trajectory weather,
  Flight Risk synthesis, root true-epoch timezone fix), **the K7 flight-deck
  brains** (XC Tracer `$XCTRC` BLE vario, circling-wind estimator, HUD stage
  selector, source ladder), and **the task-planning UX overhaul** (see
  [Tasks & waypoints](#tasks--waypoints)). The render-safety invariant
  (`utils/geo/GeoJsonSafe.kt` — sanitize NaN/Inf at the GeoJSON boundary) shipped,
  killing the delete-waypoint crash class structurally.

### What's genuinely open (Phase 2 remainder + Phase 3)
- **K7 vario hardware bits:** vario picker + device-memory (scan→pick→persist
  MAC so multiple varios on launch don't grab the wrong one) and in-flight checks
  (live wind while circling, GPS hand-off) — need on-device validation.
- **K2 airspace "Timely":** vertical-proximity readout ("180 m below CTR floor")
  + trajectory look-ahead — ⏸ deprioritized 2026-06-21 (the declutter/relevance
  half that fixed the "wallpaper" problem shipped).
- **Smaller gaps:** reliable tap on dense overlapping clusters, corridor-DEM
  terrain clearance.
- **Phase 3 stability hardening:** overlay S1 multi-hour soak (promote probe to
  gate), S5 one-line `BuildConfig.DEBUG` gate on `PerformanceDebugger`.

### Next-focus candidates (decision pending)
1. **SOS one-button (Epic 01 1.4–1.7)** — the untouched half of the core mission
   ("signal for help"). Most mission-aligned.
2. **Finish Epic 04 onboarding** — region-from-GPS + release build are started;
   pairing robustness / OTA / Play Store turn validated tech into something a
   stranger can set up. (Epic 01 MVP, its gate, is met.)
3. **Spedmo cloud layer (Epic 03)** — now unblocked; offline foundation built.
4. **Polish open product gaps** — K7 vario hardware, dense-cluster tap, terrain.

---

## Epic 01 — Pilots see each other and signal for help without cell service

**Priority: now.** The core safety epic. Tern keeps working without LoRa
hardware; the board is an addition, not a requirement.

**What done looks like:** peers on the map without cell service; "last seen" age;
one-button SOS everyone in range sees; OLED shows link/peers/beacon/battery and
confirms SOS; graceful "no LoRa" / "no peers" with no error modals.

**Out of scope (for this epic):** pilot text messaging; hardware-only operation /
board-side SOS button; ground-crew tracking (→ Epic 03); custom radio/protocol.

### Stories

**1.1 — Pair with a board (QR / BLE) — ✅ done** (2026-05-27, hardware-verified).
QR → `tern://` deep link → BLE scan → GATT → MTU 517 → claim on PRIVATE_APP
(256) → ownership persisted → BLE NO_PIN→FIXED_PIN. Auto-reconnect ✅ (PR #20,
reliability suite T2/T6/T7). **Board rename + config from Settings ✅ (2026-06):**
pencil/trash controls; rename via `set_owner` (admin field 32) reaches OLED,
persists across reset, propagates to buddies; display config (screen timeout /
flip) editable read-modify-write; hardware-verified both boards.
*Remaining:* ⬜ release packet on "Forget Board" (firmware handler exists; app
doesn't transmit it). Wire protocol detail → [QR pairing reference](#reference--qr-pairing-wire-protocol).

**1.2 — My position broadcast over LoRa — ✅ done** (hardware-verified 2026-06).
Persistent BLE + ToRadio/FromRadio codec + GPS→Position→ToRadio. Uses
Meshtastic POSITION_APP (portnum 3); no custom format. Silent when no board.

**1.3 — Other pilots' positions on my map — ✅ done** (hardware-verified 2026-06,
exceeded scope). FromRadio decode → PeerState → Redux → map, verified on the
three replayed flights. Delivered beyond "marker":
- **One always-on, glanceable peer HUD** (`PeerLayer.renderMarkerBitmap`):
  callsign, staleness puck + glyph, heading/track arrow, relative altitude,
  distance, ground speed, climb. **Reworked 2026-06** — SAFETY/CLIMB/TACTICAL
  view-modes *removed* (two taps to switch = unusable in flight); now one rich
  marker, decluttered by proximity+density (`PeerBundleBuilder` FULL/MEDIUM/
  REDUCED), bold, shown by default at flight zoom (`ZOOM_FULL` 11→9). Pull-up
  team sheet remains the ground roster.
- **Off-screen buddy indicators** (`OffScreenPeerIndicators`): edge chips
  (callsign + distance) so a peer is never silently lost on wide XC.
- **Names propagate on change** (Meshtastic owner name); team-sheet "Xs ago"
  ticks off a real wall clock.
- **Two-pilot end-to-end over real LoRa ✅** — synchronized two-device Bir
  Billing replay (Ulefone=Richard/LilyGo, Pixel=Barney/Heltec) confirmed the
  live marker when near + off-screen chip + real distance when ~1.5 km apart.

**1.4 — One-button SOS, broadcast + receive — ⬜ todo.** Glove-friendly control
with confirm; board broadcasts SOS + last position; receivers pop a
high-priority alert; SOS packet more robust than position (repeat until ACK).

**1.5 — OLED shows radio status at a glance — ⬜ todo** (firmware). Link state,
peer count, last beacon age, battery — readable at arm's length, low lag.

**1.6 — OLED confirms SOS sent/received — ⬜ todo** (firmware). Distinct
"SOS SENT" / source-on-receive; persists; clears on phone ack.

**1.7 — Graceful no-board / no-peers / disconnect — ⬜ todo.** No blocking errors;
auto re-pair on return; glanceable-but-quiet "no LoRa" / "no peers" indicator.

**Open questions:** beacon cadence vs board battery; realistic LoRa range in PG;
SOS retransmit policy. (Protocol = Meshtastic; transport = BLE only — both
decided.)

**Team formation direction (2026-06-27):** a mesh team = a Meshtastic channel
(name + PSK). Rather than build a bespoke team-management UI here, **team identity
is delegated to Spedmo** — joining a Spedmo group provisions the channel + PSK to
the board (Epic 03 organizing principle + Story 3.9). This epic owns the transport
(channel join/`set_team`/`set_channel`); Epic 03 owns the social membership.

---

## Epic 02 — Pilots see nearby aircraft and are seen by them

**Priority: later. Status ⏸ shelved until V2 dual-radio hardware (2026-06-27).**
Aviation traffic awareness (FANET/FLARM/ADS-L) so a paraglider sharing air with
a glider/helicopter knows "is anything about to hit me?"

**Why shelved:** the whole epic hinges on the unproven single-radio gap-scan
(Story 2.10) — hearing FANET/FLARM on the current LilyGo means leaving the mesh
frequency periodically, at an unknown cost to buddy-mesh delivery. The clean
answer is the **V2 custom board's second ~$3 SX1276** dedicated to traffic RX
full-time, leaving the primary radio undisturbed. Rather than invest in a
gap-scan experiment that V2 hardware makes moot, we wait for V2. The buddy-mesh
gate (Epic 01 MVP) *is* met, so this is a deliberate hold on value, not a
blocker. The app-side Phase A (2.1–2.4, CPA brain) is also held — it has nothing
to feed it until a traffic source exists.

**Pick back up when:** the V2 dual-radio board exists — then start with FANET
(open, paragliding-native, no legal risk) RX on the dedicated radio, plus the
Phase A app layer in parallel (swarm-sim testable, needs no hardware).

---

<details>
<summary>Full Epic 02 plan (kept for when V2 lands)</summary>

**Three layers:** upstream Meshtastic broadcast PRs (general-purpose) · Mezulla-
fork gap-scan receive (risky, our go/no-go) · Tern app render+CPA+audio.

**Out of scope:** ADS-B 1090 MHz (needs separate IC); full FLARM trajectory
prediction (simple CPA dot-product is enough); dual radio (v2 hardware — promoted
only if gap-scan proves unviable in 2.10).

### Phase A — Tern app (Mezulla-specific, swarm-simulator testable) — all ⬜
- **2.1 Traffic data model** — `TrafficContact` (pos/alt/speed/climb/heading/type/
  protocol/staleness) + bounded redux traffic slice; synthetic events via
  `SwarmSimulatedConnection`; auto-age-out.
- **2.2 Traffic on the map** — markers distinct from peers; aircraft-type icon;
  age/fade like peers.
- **2.3 CPA + collision alert** — rel-pos·rel-vel → time → distance; alert when
  CPA < threshold & TTC < horizon, with direction + rough distance; honest
  false-alarm rate on IGC replays.
- **2.4 Audio alert on Mezulla speaker** — tone distinct from buddy/SOS/vario;
  audible in cockpit wind noise.

### Phase B — firmware (upstream broadcast + fork receive) — all ⬜
- **2.5 Upstream engagement** — RFC/discussion: message type + port allocation,
  module API boundaries, acceptable mesh impact.
- **2.6 Bench-test harness** — two devices; baseline delivery, retune latency,
  loss with module active; later extended to capture rate. Reusable per story.
- **2.7–2.9 Broadcast own position** on FANET / FLARM Legacy / ADS-L — airborne
  gate (ground never TX); aircraft type configurable; each with bench report.
- **2.10–2.13 (fork) Gap-scan receive** FANET / FLARM / ADS-L + surface into mesh
  — 2.10 is the feasibility proof (mesh loss vs baseline → go/no-go vs dual radio).

**Future (v2 hardware):** dual radio (dedicated 2nd SX1276) — promoted if 2.10
shows single-radio gap-scan isn't viable.

**Open questions:** gap-scan duty cycle; FLARM key schedule currency; ADS-L Issue
2 stability; module retune support; CPA thresholds (start 500 m / 30 s); accept-
able mesh-loss bar. **Refs:** `docs/hardware/traffic-awareness.md`, SoftRF,
GXAirCom, FANET spec.

</details>

---

## Epic 03 — Spedmo social layer — pilots feel less alone

**Priority: later** (starts after Epic 01 MVP — **now satisfied**). Spedmo
(spedmo.com — Spring + Postgres REST API, ~1000 sites, IGC storage, live
tracking) is a friend's platform Tern plugs into for cellular-relayed enrichment.
**Tern stays fully offline-first; Spedmo is purely additive.** (Raghu has the
Spedmo source + a direct relationship, so the partner-API surface can be shaped
to fit, not just consumed — the usual third-party-API risk is low here.)

**Organizing principle (2026-06-27) — Spedmo is the team/identity backbone.**
Don't reinvent buddy-team management; ride Spedmo's existing competitions / teams
/ social groups. **Adding a buddy to a team should be as simple as joining a
social group on Spedmo.** Layering: *Spedmo = identity & membership* (online, set
once, cached) · *LoRa mesh (Epic 01) = offline-first transport* (live tracking in
the air, no cell) · *Spedmo cell-relay (3.4) = fallback* for buddies out of mesh
range. Decoupled in failure (no cell → mesh still works; no board → cell-relay
still works). **The crux unsolved piece:** a mesh "team" is a Meshtastic channel
(shared name + PSK via `set_team`/`set_channel`), so a **Spedmo group must map
deterministically to a channel + PSK, provisioned to the board on join.** That
binding is what makes "join a group → buddies appear" work; it doesn't exist yet.
This elevates **Story 3.9** from a cosmetic "club members brighter" item to the
spine of the buddy-onboarding UX (and ties Epic 03 ↔ Epic 01).

**What already exists in Spedmo (verified 2026-06-27 from `src/paragliding`).**
The partner API and the social model are mostly built — Epic 03 is far less
greenfield than assumed:
- **Partner REST API** `ApiV1Controller` (`/api/v1.0/*.api`), Swagger-documented:
  `POST flightDataUpload.api` (**IGC upload + FAI scoring — the 5.4/3.5 target,
  done**), `POST livetrackUpdate.api` (push my live pos — **3.4 TX**), `GET
  livetracks.api` (recent global — **3.3**), `GET flights.api` / `flightData/{id}.api`
  (my flights + IGC), `GET member.api` (**3.1**).
- **Auth = two headers:** API key (partner-app identity, `model.Api`) + member
  access key (`ApiMember`); issued via `apiAuthorise.pg`; cache once → works
  offline. Functionally covers 3.1 without a bespoke OAuth flow.
- **Team backbone already modeled:** `Club` (name/description/`privateClub`/
  members/watched-sites), `ClubMember` (club↔member+`type`), `ClubInvitation`
  (invites). **`LiveTrackingService.findByRecentByClub(club)` already returns
  livetracks scoped to a club's members** — the exact "see my buddies live" query,
  written but not yet on the v1 API.
- **Remaining server work (small, we own the source):** (1) expose
  `findByRecentByClub` as a club-scoped livetracks endpoint; (2) expose "my clubs"
  on the API (`ClubsController` is web-only today); (3) **the channel/PSK binding**
  — derive/serve a Meshtastic channel name+PSK per club (the one genuinely new
  piece); (4) "landed safe" (3.6 — no equivalent yet).
- Source pointers: [[tern-spedmo-codebase-map]].

**What done looks like:** night-before "who flew my site this week"; at-launch
"who's in the air nearby" + recent landed-pilot notes; in-flight faded markers
for out-of-mesh buddies via cellular; auto IGC upload + XC score + "landed safe"
to ground crew — all with zero manual action and silent offline degradation.

**Out of scope:** in-flight mesh comms (that's Epic 01); replacing offline maps/
airspace/sites; a from-scratch social network; anything that breaks without cell.

### Stories — all ⬜ unless noted
- **3.1 Account link (one-time OAuth)** — link once (FB/Google/Apple), cache
  UUID+key in Android Keystore; works offline thereafter.
- **3.2 Silent site enrichment** — prefetch+cache Spedmo site metadata on map
  idle near known sites; offline = cached, never a spinner.
- **3.3 Who's flying here now** — pre-flight card of pilots active in livetracks
  within ~50 km (opt-in only); silent offline.
- **3.4 Cell-relayed peer markers** — when paired + on cell, fetch buddy
  livetracks, render as *faded* "via cell" markers distinct from mesh peers;
  conservative refresh; vanish on no-cell.
- **3.5 Auto IGC upload after landing — 🟡 Tern-side built (2026-06-27), on-device
  verify ⬜.** Landing detection queues upload for next signal; survives restart;
  retries. **Server side done:** `POST /api/v1.0/flightDataUpload.api`
  (`ApiV1Controller`) accepts IGC, imports + FAI-scores it. **Tern side built:**
  `spedmo/SpedmoApi` (uploadIgc/getMember, two-header auth), `SpedmoCredentials`
  (member key in its own prefs; partner key via gitignored `spedmo.properties` →
  BuildConfig; auto-upload default OFF), `SpedmoUploadQueue` (file-backed,
  retry/backoff, restart-safe — 8 JVM tests), `SpedmoUploader` (drains on seal +
  connectivity + manual), wired into `FlightRecordingMiddleware` seal path +
  crash-recovered orphans. UI: Settings "Spedmo" link/opt-in section, logbook
  per-flight status + manual upload. *Remaining ⬜:* a real partner key + a test
  member key, then end-to-end on-device verify (the feature is inert until a key
  is present). This is **Epic 05 5.4** on the Tern side.
- **3.6 "Landed safe" to ground crew** — one-tap watcher setup; fires within 30 s
  of landing when cell available; deferred send otherwise; manual re-fire.
- **3.7 SOS forwarding (defense in depth)** — mesh SOS primary (offline); also
  push to Spedmo when cell up; never weakens the mesh path.
- **3.8 Pilot reports** — post-landing quick conditions; decay after 12 h; soft
  site annotations; queue offline.
- **3.9 Spedmo groups → buddy teams (the team-identity backbone, not just a list).**
  Joining a Spedmo competition / club / social group *is* how you form a buddy team:
  the group roster becomes the team, members are brightest in the peer view and the
  default scope for social features. **Core sub-piece:** map a Spedmo group →
  Meshtastic channel + PSK and provision it to the board on join (see the Epic 03
  organizing-principle note) so the offline LoRa mesh shows exactly that group. The
  `MapState.teamSource="spedmo-club"` hook already exists; the channel-provisioning
  bridge to Epic 01 (`set_team`/`set_channel`) is the new work.
  - **🟡 Built 2026-06-27/28 (deploy + on-device verify ⬜).** *Spedmo side* (PR
    branch `tern-team-channel`, proven by ClubServiceTest 3/3 + a 100k-PSK run, not
    yet pushed): `GET /api/v1.0/clubs.api` returns the member's clubs each with a
    team `channelName` + `psk` (random 32-byte, **hex**, minted once per club +
    persisted — a stable secret all members share). *Tern side* (built + tested):
    `SpedmoApi.listClubs`, `TeamLink.fromHex` (club name+hex PSK → Team), and
    Settings → "Team from your Spedmo club" (load clubs → pick → `SetTeam(source=
    "spedmo-club")` → existing set_channel apply path provisions the board).
    *Remaining:* push + deploy the Spedmo PR, then fetch-clubs→join-channel on
    device with two phones.
- **3.10 Soarable forecast from Spedmo — 🟡 offline fallback ✅ / source ⬜.**
  The K4 weather deck shipped `TernLocalFlyability` *and* the **soarable-window
  scan** (`weather/Soarable.kt` — best contiguous flyable run, daylight-bound,
  marginal-direction fallback, daily digest, no-sun degradation), claim-tested
  ×5, **deliberately tuned to agree with Spedmo's probability model** (claims.md
  K4 names it "Tern's offline fallback for the Spedmo soarable forecast"). The
  glass-cockpit PG-spot weather UI (soarable card, orientation dial, 48 h chart
  with soarable shading) is live. **Remaining = the Spedmo source side only:**
  pull+cache Spedmo's answer online and degrade to the local scan offline (same
  `FallbackWeatherAPI`/`WeatherSourcePolicy` pattern), gated on Spedmo exposing an
  **authenticated partner API** + a **site-id resolver** (PGE/lat-lng → Spedmo
  site id). Existing endpoints (session AJAX today): `POST /json/getForecast.pg`,
  `POST /json/getHourlyForecast.pg`. (Raghu to arrange — friend's platform.)

**Open questions:** OAuth round-trip mid-trip (solve with home setup + forever
cache); privacy defaults (live-tracking + auto-upload off by default); rate
limits (prefetch on idle, batch per area); report-spam controls.
**Spedmo source:** `/home/raghu/src/paragliding/` (private).

---

## Epic 04 — First-time onboarding — the 7-step brochure

**Priority: soon.** Epic 01 is technically capable today; this epic removes the
onboarding rough edges between "an engineer can do it" and "a paragliding group
can do it." **Acceptance:** a non-engineer, given only the brochure + a sealed
Mezulla, reaches "Connected ✓" within 10 minutes (verified by handing it to a
non-engineer friend and watching).

> The brochure: charge → install Tern → power on → scan QR → "Open in Tern" →
> wait ~30 s → done. No laptops, no CLI, no jargon.

**Out of scope:** hardware packaging/charging logistics; buddy-mesh feature work
(Epic 01); Spedmo (Epic 03); iOS.

### Stories
- **4.1 Boards ship pre-flashed + pre-configured — ⬜ todo** (depends on hardware
  fulfillment; coordinate with whoever ships). Pilot never touches `pio`/`esptool`.
- **4.2 Auto LoRa region from phone GPS — 🟡 substantially built.** Region
  auto-set from phone GPS via standard `AdminMessage.set_config` (port 6, full
  LoRaConfig + tx_enabled). *Remaining:* verify the GPS→ITU-region mapping
  coverage and add the Settings "change region" affordance. Wrong region = illegal
  TX, so the mapping must be right.
  - **Correcting a wrong region needs no erase** — re-push the corrected full
    `LoRaConfig` via `set_config`; it overwrites NVS and applies live. (A wrong
    region is therefore self-healing for a pilot; just re-pair / re-push.)
  - **Refinement — use `factory_reset_config`, drop the erase-flash crutch.**
    Returning the board to the pristine `UNSET` state (needed only to re-exercise
    the "auto-set on first pair *if* `UNSET`" trigger during dev) does **not**
    require `esptool erase_flash`. That was a *tooling gap*: the app only knows how
    to *set* a region, never to reset, so erase+reflash (`scripts/reset-mezulla.sh`)
    became the catch-all. The firmware already exposes clean admin paths the app
    should drive instead: **`factory_reset_config` (admin tag 99)** →
    `installDefaultConfig()` leaves region `UNSET`; **`factory_reset_device` (94)** /
    **`nodedb_reset` (100)** for the device/nodeDB + ownership side. Wiring these
    (plus the 0x03 release packet — see Epic 01 1.1) removes the only reason a
    pilot would ever need a physical erase-flash.
- **4.3 Tern in Play Store — 🟡 groundwork done.** Release build + real signing +
  R8 shrink shipped; [Publishing checklist](#publishing-checklist) written. *To
  do:* real upload key + Play App Signing, AAB, privacy policy + data-safety form,
  listing assets, internal-testing track. (Deferred until a Play account exists.)
- **4.4 Pairing UX handles bond/screen/retry automatically — 🟡 partial.**
  Auto-reconnect + reliability suite done; *still to make invisible:* wake screen
  on pair, clear stale bond for the MAC, auto-retry recoverable failures with
  backoff, pilot-readable failure messages (not "GATT 133").
- **4.5 Eliminate the BLE PIN popup — ⬜ todo.** Clean pair from a clean phone
  never shows it; if it appears, detect + auto-dismiss or instruct (or drop the
  FIXED_PIN fallback in firmware).
- **4.6 Concurrent multi-pilot pairing — ⬜ todo.** 4+ buddies pairing at once in
  BLE range; each claims only its own QR'd board; no degradation with crowd size.
  (Two-phone pairing exercised this session; 4-way concurrency unvalidated.)
- **4.7 OTA firmware update from Tern — ⬜ todo.** Check on launch; notify; update
  over BLE/WiFi (dual-bank, resumable, signed, never bricks); history in Settings.
- **4.8 Settings → Mezulla status at a glance — 🟡 partial.** Rename/config
  controls landed (1.1); *remaining:* corner link badge → tap opens Settings →
  Mezulla; populate link/battery/beacon/peers/firmware/update fields (data flows;
  this is a rendering story); one-tap recover (Forget / Re-pair).
- **4.9 Web flasher — self-serve first-flash of an off-the-shelf board — ⬜ todo.**
  *The interim hardware story until the custom nRF52 board (and/or 4.1 pre-flashed
  fulfillment) exists.* A pilot **buys a standardized board on Amazon** (pin one
  model — **Heltec LoRa 32 V3 / ESP32-S3**, so everyone's on one known-good firmware
  variant) and flashes Tern firmware themselves via a **`ternparagliding.com/flasher`
  web page** (ESP Web Tools / Web Serial), then pairs + configures over BLE as usual.
  - **Why a web page and not "flash over Bluetooth from the app":** a factory ESP32's
    ROM bootloader speaks **USB/UART only — there is no BLE in the factory bootloader**,
    so the *first* flash of a blank board **cannot** happen over Bluetooth. BLE OTA
    (4.7) only works *after* Tern firmware + the `bleota` helper are already on the
    board. So first-flash = a one-time USB step; everything after is BLE (pair → config
    via 1.1/4.2 → updates via 4.7).
  - **Hard constraint — desktop only:** Web Serial works on **desktop Chromium
    (Chrome/Edge)**, **not Android Chrome** and **not Firefox/Safari**. So the flow is
    "plug the board into a *computer*, open the flasher page, click Flash" — not from
    the phone. (Phone-only first-flash would need an in-app **USB-OTG esptool**
    implementation — much larger effort, parked.)
  - **Live pre-flight checklist on the page** (the asked-for self-diagnosing UX):
    - *Tier 1 (auto on load, no device):* Web Serial support (`'serial' in navigator`),
      secure context (`isSecureContext` — HTTPS ✓ via Cloudflare), **desktop-vs-phone
      block** (UA → hard-stop with "open on a computer"), browser identity (Safari/FF →
      "use Chrome/Edge"). Render as green-check / red-X rows; gate the Flash button.
    - *Tier 2 (on Connect):* chip detection — esptool reads chip type + MAC + flash
      size; **chip-family validation is largely free via ESP Web Tools** (a manifest
      that ships only an **ESP32-S3** build auto-rejects an older ESP32). Show
      "Detected ESP32-S3 ✓".
    - *Honest limits:* the silicon reports **chip family, not board model** — can't
      verify "Heltec V3" exactly (two S3 boards are indistinguishable), so guard on
      family + trust the buy-this-model instruction. **Drivers can't be probed** — if
      no port appears, show reactive troubleshooting (data cable not charge-only,
      CP210x/CH340 driver links, hold BOOT, try another port).
  - **Build:** static `site/flasher.html` in the **ternparagliding-web** repo (same
    nginx/Cloudflare) = ESP Web Tools button + `manifest.json` (firmware/bootloader/
    partition/littlefs/bleota at correct offsets) + the checklist UI. The page can be
    scaffolded now; only the **built firmware `.bin`s** need dropping in.
  - **Lifecycle:** bridges **4.1** (pre-flashed, needs inventory) and **4.7** (BLE OTA
    for subsequent updates). The future **custom nRF52 board retires this** — nRF52
    supports clean **BLE DFU from blank**, so true tap-to-flash-from-the-app becomes
    possible and the USB web step goes away.

**Order of attack:** 4.4+4.5+4.6 (pairing robustness — the reason pilots fail
today) → 4.8 (status confidence) → 4.2 (region) → 4.7 (OTA, unblocks 4.1) → 4.1
(pre-flash) → 4.3 (Play Store, parallelisable). **4.9 (web flasher)** is the
interim self-flash path — schedulable independently (it's web work, not app work),
needed once real pilots start buying their own Heltec boards.

**Not in this epic:** duplicate-BleConnection race (→ Epic 01 1.1 robustness);
`MEZULLA_TEST_BUILD` (test infra, never shipped).

---

## Epic 05 — Flight recording, logbook & export

**Priority: now. Status 🟡** (launch→deck ✅; record/logbook/export/upload ⬜).
The flight lifecycle on the deck: detect the launch, record the whole flight,
keep it in a local logbook, and — if a Spedmo profile is set up — upload it. The
XC Tracer vario (K7) now gives us a real positioned-vario stream to record, which
is what makes this worth doing properly.

**The unknown it converts:** "did this flight get captured, and where is it?" A
pilot lands and *knows* the track is saved, replayable, exportable, and (if they
opted in) already on its way to Spedmo — no GPS file they'll forget to upload.

**What done looks like:**
- The deck switches to flight mode on launch by itself (done) and starts
  recording without a button press.
- After landing the flight is finalised, saved locally, and listed in a logbook
  with at-a-glance stats; the pilot can replay it on the map and export it.
- Export produces a valid **IGC** (and at least one of GPX/KML/CSV) that other
  tools (XContest, SeeYou, Spedmo) accept.
- If a Spedmo profile is linked, the flight auto-uploads when cell is available;
  the logbook shows queued / uploaded / scored. With no profile, nothing leaks —
  it just stays local.

**Out of scope:** competition-grade *validated* IGC (Tern isn't an IGC-approved
flight recorder — the G-record won't be FAI-secure; fine for logging + Spedmo);
the Spedmo account/OAuth plumbing itself (Epic 03 3.1); the deck *instruments*
(vario/wind/HUD — built under K7, see below).

### Stories

**5.1 — Detect launch + switch to flight-deck mode — ✅ done.** `FlightDetector`
(pure, motion-based: sustained ground speed ≥ 2.5 m/s *or* ≥ 12 m above the launch
datum, 3-fix confirm, latched for the session) drives phase-aware camera-follow
(auto-zoom / track-up engage only once airborne, never while rigging on launch).
The climb-tinted `FlightTrack` breadcrumb renders the recent path. *(K7,
on-device verified.)*

**5.2 — Record the flight + export — 🟡 core built, wired, JVM-tested (2026-06).**
*Built + claim-tested (16 tests):* `flight/recording/` — `FlightRecorder` (full-
fidelity, raw-tap), `RecordingModel` (own track + buddy + event sidecar),
`LandingDetector`, `AbnormalEndDetector`, `FlightSummary`, `FlightStore` (crash-
survivable append-only `.live.jsonl` + sealed `.flight.json` + `recoverOrphans`),
`FlightSigner`/`DigestFlightSigner` (tamper-evidence over canonical bytes);
`flight/export/IgcWriter` (round-trips through `IgcParser`); `FlightRecording
Coordinator` ties it together. **Wired into the app** via
`redux/FlightRecordingMiddleware` (taps `UpdateVarioFix` + `PeerPositionReceived`,
runs its own `FlightDetector`, ordered single-thread IO off the main thread).
*Real-data finding:* a normal XC's spiral-to-land tripped the rapid-descent seal →
changed to a **bookmark event** (recording continues; only landing/SOS/manual
seal). *Remaining ⬜:* hardware-Keystore signer (digest is the shipped baseline) +
server counter-sign (5.4); manual "end flight" control; **on-device verification**.
Design detail below kept for reference.
- **Architecture — recorder is the source of truth; the ring buffer is a view.**
  `FlightTrack` stays as-is for the live thermal-map (a decimated, bounded ~10 km
  display projection). The recorder is a **sibling off the same fused-fix stream,
  not chained to the buffer's eviction.** This matters: the ring buffer keeps a
  point only if it's ≥5 m from the last, so feeding the log from what it *discards*
  would inherit that spatial decimation and throw away exactly the slow-moving
  fixes that show a thermal climb — and IGC / XContest / Spedmo want time-based
  ~1 Hz fixes, not spatial thinning. So: tap the **raw** stream, keep **every**
  fix, write to disk **incrementally** (a crash/kill mid-flight must not lose the
  flight). (The "nothing is lost when the buffer discards" goal is met by the raw
  tap; if we ever truly want offload-on-evict instead, the buffer would have to
  stop decimating, which defeats its rendering purpose.)
- **Per-fix fidelity.** Capture time, lat/lon, **baro + GNSS altitude**, ground
  speed, heading/track, fused vario, and `source` / `uncertainty` / `quality`
  (per [../design/flight-state.md](../design/flight-state.md) — faithful replay,
  see which sensor was live). Today `FlightTrack` keeps only time/lat/lon/climb.
- **Flight memory (Tern-native sidecar) — more than my own track.** Alongside the
  IGC-exportable own-track, record the **context that made the flight**: buddy
  (peer) positions over time, cylinder tags / task progress, the live wind
  estimate, airspace proximity events. Enables **post-flight gaggle replay**,
  incident reconstruction, and real-world **swarm-sim fixtures** — this is the
  overlay-audit "log peers in flight recording" follow-up, now homed here. IGC
  stays single-pilot (what goes to Spedmo); the sidecar is for in-app replay only.
  (Privacy: buddy fixes live only in the *local* memory file — only your own track
  is ever uploaded.)
- **Black-box / dashcam integrity (the incident case).** Treat the memory file
  like a car dash-cam used for an insurance/SAR claim: it must be credible *after*
  a bad event, so —
  - **Crash-survivable:** incremental flushed writes (covered above) so the record
    is intact right up to the last fix even if the phone dies on impact; the file
    is recoverable/openable even when never cleanly finalised.
  - **Seal on abnormal end, not just on landing:** an SOS fired (Epic 01 1.4), an
    impact-like deceleration, or a sustained rapid descent should **bookmark** the
    moment and ensure the segment is flushed + sealed — the evidence must capture
    the event itself, not stop short of it.
  - **Tamper-evident — two tiers (both fit offline-first):**
    - *On-device, offline (immediate):* sign the sealed file + chain-hash the fix
      records with a **hardware-backed Android Keystore** key (StrongBox/TEE) —
      generated on-device, **non-exportable**, so far stronger than any embedded
      key. Attach **Key Attestation** (cert chain to Google's attestation root) to
      prove the signature came from genuine secure hardware, not a software forgery.
    - *Server-side (when online): the authoritative upgrade.* On upload, have the
      Tern/Spedmo backend **counter-sign + RFC-3161 timestamp** the flight hash
      with a *server-held* (HSM) key — a key the user never had, so the record
      can't be forged or **back-dated**. This is the same "authority holds the key
      server-side" pattern as Play App Signing.
    - **Why not the Play app-signing key:** Google holds that private key in their
      HSM; it never ships in the APK and isn't ours to use, and an embedded key
      would be extractable from the APK (a secret shipped to every phone isn't a
      secret). It's a *code*-signing identity, not a data-signing one.
    - **Honest limit:** this proves "unmodified since signing" + "from this
      hardware/authority" — **not** that the inputs were truthful (the owner
      controls the phone and could feed spoofed GPS *before* signing). So:
      dashcam-grade / corroborating, attested + server-timestamped — **not**
      FAI-certified (that needs sealed tamper-resistant recorder hardware, which is
      why a phone IGC G-record is "not validated").
  - **⬜ RESEARCH LATER — keyless "democratized" authenticity (chain + witness +
    anchor) as an alternative to PKI.** Open question raised 2026-06-27: instead of
    hardware-Keystore + cert signing, lean on three keyless mechanisms, two of which
    Tern already produces: (1) **hash-chain** the fix records (tamper-evidence, no
    keys — the digest baseline is most of this); (2) **multi-party witnessing** —
    buddy mesh peers already log each other in the flight-memory sidecar, and Spedmo
    club-mate livetracks corroborate server-side once the team backbone lands, so
    Spedmo becomes a notary for free; (3) optional **public timestamp anchor**
    (e.g. OpenTimestamps → Bitcoin, free, no central authority) — can ride the same
    upload queue as 5.4. Keep IGC for interop; the authenticity layer rides as a
    format-free sidecar (matches flight-memory). Same honest limit as PKI (can't
    prove the GPS input was real — only corroborate it), but no key custody / CA /
    cert workflow, and corroboration is harder to fake than a lone signature. PKI
    drops to "only if FAI certification is ever needed." **Needs a research pass
    before adopting** (OpenTimestamps client cost/latency, witness-record format,
    collusion handling).
  - **Retention:** incident-sealed flights are never auto-purged; the logbook (5.3)
    can mark a flight "protected." Ties to Epic 01 1.4 (SOS) + Epic 03 3.7 (SOS
    forwarding) — the record corroborates the alert.
- **Start/stop.** Start recording on launch (5.1's airborne latch); **finalise on
  landing.** Landing detection is new — `FlightDetector` never detects it (a
  mid-flight flicker to "grounded" would be worse than leaving follow on). Use the
  Epic 03 3.5 rule (ground speed ≈ 0 + baro stable ~5 min) purely to *close* the
  recording, plus a manual "end flight" fallback.
- **IGC writer.** We have an IGC *reader* (`IgcParser`) and `IgcToXctrc`, but **no
  writer.** Add one: A/H/I headers (pilot, glider, datum, fix accuracy), B records
  (time, lat/lon, baro + GNSS alt), a G security record (self-signed, clearly not
  FAI-approved). Plus one portable format (GPX or KML) and optionally CSV.
- **Backed by a claim-driven test:** replay a known IGC through the recorder →
  write IGC → re-parse → assert the round-trip preserves the track within
  tolerance (and that gaps are honest, the same rule `FlightTrack`/`IgcToXctrc`
  use); and that a multi-pilot replay captures each buddy in the sidecar.

**5.3 — Local flight logbook — 🟡 built; on-map replay + on-device verify ⬜.**
Store + stats (`FlightStore.listSummaries()`, `FlightSummary`) and the **Compose
`LogbookScreen`** are built and compile-clean: Settings → "View flight logbook" →
newest-first cards (date, duration, distance, max alt, buddy-count, PROTECTED
badge) → tap for detail (full stats) → **export IGC** (FileProvider share) +
**delete** (incident-protected flights confirm first); integrated into the
prioritised BackHandler. *Remaining ⬜:* on-map replay (reuses the IGC replay
path), rename, and **on-device verification** of the whole surface.
- List rows with at-a-glance stats: date, launch site (resolve from PG-spot DB /
  geocoder), duration, free distance + XC-ish distance, max altitude, max climb,
  top of climb. Derive from the recorded track (reuse `FlightMetrics` /
  `FlightComputer`).
- Open a flight → **replay it on the map** (the deck bench replay path already
  exists for IGC) + a track summary.
- Manage: rename, delete, **export/share** (Android share intent on the 5.2
  writer output), and re-upload (5.4).
- Settings entry point (the contextual-sharing plan already names a "Flight /
  Logbook" home).

**5.4 — Upload to Spedmo when a profile is set up — 🟡 built (2026-06-27),
on-device verify ⬜** *(bridges to Epic 03 Stories 3.5 + 3.6).* This epic owns the
**Tern-side hand-off**; the Spedmo account/upload plumbing lives in Epic 03.
- ✅ On finalise, if a Spedmo profile is linked (Epic 03 3.1) **and** the pilot
  opted in (privacy default = off), the IGC is queued; sent when cell is available;
  survives restarts; retries with give-up cap. (`spedmo/` package +
  `FlightRecordingMiddleware` seal hook; reuses `IgcExporter`.)
- ✅ Logbook shows per-flight state (local-only / queued / uploaded / failed) +
  a manual upload/retry; Settings has the link + opt-in. *(scored state ⬜ — needs
  the upload response surfaced.)*
- ✅ No profile or not opted in → stays local, silently. Inert with no partner key.
- ⬜ End-to-end on-device verify (needs a real partner key + a test member key).
- ⬜ The "landed safe" ground-crew ping (Epic 03 3.6) rides the same landing event.
- See implementation detail under **Epic 03 Story 3.5**.

**5.5 — Deck instrument hardening (the remaining K7) — 🟡 partial.** The deck
*brains* — XC Tracer `$XCTRC` BLE vario ingest, the circling `WindEstimator`,
fused vario/altitude/wind HUD, HUD stage selector, source ladder — are **built
and mostly on-device verified** (see Current focus). Homing the leftovers here:
- ⬜ **Vario picker + device memory** — scan → pick → persist the MAC so multiple
  varios on launch don't grab the wrong one. Needs hardware to validate.
- ⬜ **In-flight checks** — live wind while circling; GPS hand-off under movement.
- ⏸ The cloudbase-gap HUD cue is built but dormant until weather cloudbase is
  threaded into the deck.

### Dependencies & related
- **Enabled by K7** (XC Tracer vario stream) — the deck brains are the data
  source; this epic is the lifecycle around them.
- **Epic 03 3.5/3.6** — the Spedmo upload + landed-safe plumbing; 5.2's
  recorder/writer is the prerequisite 3.5 was waiting on ("export TBD").
- **In-flight task interactions** + **Tasks & waypoints** — share the deck and the
  IGC replay path used to verify recordings.
- Design: [../design/flight-state.md](../design/flight-state.md) (the recorder is
  one of its consumers), [../design/flight-deck-ui.md](../design/flight-deck-ui.md).

---

## Tasks & waypoints

**Priority: now. Status ✅** (pilot-grade; small polish items remain). Merges the
former "routes-production" + "task-vocabulary-and-sharing" epics. Every feature
is backed by a claim-driven test (assert the **pilot-visible outcome**, never a
Redux flag — "assert downstream, not upstream"). See [../claims.md](../claims.md).

### Vocabulary (canonical — "Task", not "Route")
This is a competition/XC tool. **A Task is an ordered sequence of Waypoints + its
rules.** The full code-symbol rename "Route"→"Task" is **done** (`b926857` +
follow-ups: `overlay.route`→`overlay.task`, `model/Task.kt`, `MAX_ROUTES`→
`MAX_TASKS`, etc.; only mesh-routing code + the `route_24` asset remain). Roles:
Launch / SSS / Turnpoint / ESS / Goal / Landing. Cylinder (tag by entering),
Speed Section (SSS→ESS), Time Gate, Leg, Task type (Open/Flat/FAI).

### What's built ✅
- **Stage C — unified Spot library:** waypoints are the unit; a task point
  references a Spot by `spotId` + per-task features (role/cylinder/gates) + an
  identity snapshot. Editing a spot flows to every task; ad-hoc map drops auto-
  create a USER spot; PG spots are first-class waypoints; references **persist**
  (the `spotId`/`description` that previously died on restart).
- **Visible task** — neon line + dark casing; **rich waypoint markers** (role
  disc + code, with name + cylinder radius + elevation + time-gate pills at the
  detailed zoom tier); FAI cylinders (fill + ring); leg-distance pills; selection
  highlight; zoom-adaptive labels. A single **waypoint flag glyph** (`nf-fa-flag`)
  means "waypoint" everywhere (map, dock, page headers, Settings).
- **Map-based editing** — tap-select (`TaskLayer.onClick`→`SelectWaypoint`,
  L1-verified) · **explicit creation only** — "Create New Task" (task list) +
  the **"Add from map" crosshair** (reuses the `LongPressMap` action with
  `forceCreate` + **ground-distance snap** to an existing spot, ~150 m, no
  duplicate stacking). **Long-press is intentionally inert** (2026-06): auto-
  creating a task on every long-press duplicated those paths and was the source
  of accidental stray tasks (the recurring "1"); the smart-suggestion dialog it
  fed was dead code and was removed. · **move-mode** reposition (tap-select →
  editor → "Move on Map" → tap new spot; chosen over press-and-hold drag, which
  felt wrong on-device — the drag gesture is intentionally unwired). Per-point
  editing (role / start gate / cylinder / rename) from the panel tile.
- **Persistence completeness** — startup restore of all cached tasks
  (`TaskPersistence` hydrate → `SurfaceNearbyTasks`); auto corridor tile
  pre-cache on add/select (`TaskPlanningMiddleware`→`TaskTileCacher`, restart-
  safe); write-on-edit + proximity resurfacing + task-id round-trip.
- **Planning intelligence** — **FAI-triangle detection** ✅ (open/flat/FAI,
  robust to the 5-point comp shape; was hardcoded to 4 waypoints; `faiPoints`
  computed); **per-leg airspace segment-intersection** ✅ (`SpatialSafetyUtils.
  taskAirspaceConflicts` flags a leg *crossing* controlled airspace even with
  both endpoints outside; Polygon+MultiPolygon+holes; names what's crossed).
- **Active-waypoint navigation (buddy-style)** ✅ — `Waypoint.description` human
  name; `activeWaypointId`/`taggedWaypointIds` with auto-advance on cylinder
  entry (`TaskNavigator`/`TaskProgressOverlay`, claim-tested on the real Bir
  Billing track); on-map active highlight; `OffScreenWaypointIndicator` edge chip
  (arrow + name + distance + required glide); XCTSK reads/writes `description`.

### Open items ⬜
- **Display FAI *points*** in the task panel (value is computed, not surfaced).
- **Task management:** rename / delete / reorder / visibility — verify + honest
  tests.
- **Share:** the generic dock Share button is removed by design; redistribute
  contextually — task-as-file (Android share intent on `TaskIOManager` export),
  task-as-QR (`generateQRCode` exists), live-position (Mezulla settings), IGC log
  (Logbook). Plumbing partial.
- **Import/export robustness:** validate compressed-XCTSK / QR roundtrip with
  known vectors; GPX import/export (optional).
- **Active-WP polish:** chip can overlap the right-edge dock when target is
  near-due-east; distinct "target" vs editing-selection styling; arrival-altitude
  make/no-make colour (needs Phase 3 final glide); decouple active task from
  selection so guidance survives dismissing the panel.
- **`CacheTilesButton`** composable exists but isn't wired into any screen
  (caching is auto-only) — wire a manual trigger into the task panel if wanted.

**Definition of done:** all the above tested + usable offline end-to-end (plan at
home → restart → fly the same task in the field, see every waypoint/cylinder,
edit on the map).

---

## In-flight task interactions

**Priority: now. Status ✅** Phases 0/1/2 built (design locked 2026-06-15;
mockups in [assets/inflight/](assets/inflight/)). The *interactive* half of task
nav — what the pilot can do **while flying**, glance-first, glove/turbulence-safe.

- **Phase 0 — map tap → waypoint hit-test → selection** ✅ (`7494a62`).
- **Phase 1 — compass rosette + tag feedback + readout** ✅ (`7494a62`). Rosette
  carries three unmistakable elements: **red N carat**, **amber wind arrow**
  spanning rim→centre→rim (downwind, dark casing), **cyan next-WP badge** (bold
  centred ordinal + bearing tail). Name+distance readout **under the compass**
  (decided). Haptic + flash on tag. Planning HUD + task panel hidden in flight.
- **Phase 2 — task ribbon + manual overrides** ✅ (`6762240`). Task button opens
  a modal ribbon (done/active/upcoming dots + NEXT read); tap a dot to **Go to**
  (new `GoToWaypoint` retargets by tagging predecessors); **Skip** / **Back**.
  Verified on the Bir Billing replay. (`TaskNavClaimsTest` + `TaskOverrideClaimsTest`,
  7 HELD / 0 BROKEN.)
- **Phase 3 — Pilot/glider profile + final-glide arrival height — ⏸ deferred
  (2026-06-15).** The green/red "can I make it" number. Pilots pick a wing from a
  bundled DB + all-up weight (ref: Niviuk Klimber 3P @ ~92 kg); arrival height =
  altitude − distance/glide, wind-corrected (live wind exists). Open: best-L/D vs
  full polar (lean best-L/D first); glider DB source; a new **Pilot** settings
  section. Until then the next-WP readout shows **name + distance only**.

---

## Firmware workstreams (Meshtastic / Mezulla)

Firmware on the LilyGo T3 V1.6.1 (ESP32-PICO-D4 + SX1276 + SSD1306, variant
`tlora-v2-1-1_6`). Phone↔board contract: `docs/architecture/mezulla-wire-contract.md`.
Repo: github.com/raghumad/mezulla-firmware (`develop` tracks upstream;
`mezulla-firmware` is our work). Each commit is upstream-worthy *or* Mezulla-
specific, never mixed. **Build env:** WSL Ubuntu (`pio run -e tlora-v2-1-1_6`);
Windows ESP-IDF component manager is broken. Flash from Windows over COM at
0x10000 (`PYTHONUTF8=1`). Both boards flashed + PRIVATE_HW verified (fw 2.8.0.33dadcd).

- **Stage 1 — stock Meshtastic** ✅ (flash only; `tern-android/scripts/flash-mezulla.sh`).
- **Stage 2 — build-from-source + QR pairing** ✅ **COMPLETE (2026-06).**
  F1.1 clone+build ✅ · F1.2 flash-from-source ✅ · F1.3 regression gate ✅ (cycle
  + reliability suites pass against source-built fw). QR pairing F2.1–F2.5
  (ownership in flash, OLED QR, claim/release/query handlers on PRIVATE_APP) all
  ✅, end-to-end claim/query verified on hardware.
- **Stage 2.5 — upstream cleanup for the pairing PR — ⬜ todo** (not blocking;
  fork works as-is). Rename Mezulla→generic (`DevicePairingModule`,
  `pairing_owner_id`, `[PAIRING]` logs); configurable URL scheme (default
  `meshtastic://pair?…`); display-conditional QR (`HAS_SCREEN` + dimensions, serial/
  BLE-advert fallback); `device_pairing_enabled` ModuleConfig flag (off by
  default); boot/memory bench tests; Meshtastic RFC/discussion first.
- **Stage 3 / 4 — traffic firmware** = Epic 02 Phase B (upstream broadcast +
  fork gap-scan receive).

**Admin field numbers (must match firmware `admin.pb.h`):** `set_owner`=32 (not
8), `set_channel`=33, `set_config`=34, `set_module_config`=35, `reboot`=97. GATT
write OK ≠ applied (nanopb silently skips an unknown oneof field). `set_config`
*replaces* the whole sub-struct → read-modify-write. `node_info_broadcast_secs`
is clamped to a 1-hour floor in firmware (hence push-on-change name propagation,
not a periodic knob).

**Firmware test mechanisms:** build scripts (compile/flash/boot binary pass-fail);
serial-log assertions (`[MEZULLA] …` structured lines); human tests (QR
readability, OLED contrast, button timing) captured as photos/video.

---

## Known issues / open product items

Not an active to-do — the starting point when focus shifts back to app cleanup.
Re-validate each via a claim-driven test as it's picked up. (Detailed pre-teardown
postmortems are archived in
[../archive/known-issues-pre-teardown-2026-06.md](../archive/known-issues-pre-teardown-2026-06.md):
Hilbert haversine fix, `icaoClass` off-by-one, dense-airspace freeze fix,
auto-download gate.)

### Resolved this cycle (kept briefly so they aren't re-investigated)
- ✅ **Back exits app from the task panel (B1)** — prioritised `BackHandler` closes
  the topmost open layer first.
- ✅ **Create-waypoint forced a full-screen modal (B2)** — a map drop flags
  `WaypointSelection.isNew`; editor opens only on tapping an existing point.
  (Drops now come from the crosshair, not long-press — see Tasks & waypoints.)
- ✅ **Dock reachability while panel open (B4)** — non-bug (top-right dock never
  overlaps the bottom panel).
- ✅ **Airspace `type=4` mislabelled "MILITARY"** — already maps `type=4 → CTR`.
- ✅ **Weather "deferred"** — shipped as the full K4 deck (Flyability, soarable,
  thermal w*, Skew-T, trajectory weather, Flight Risk, true-epoch timezone fix).
- ✅ **Delete-waypoint NaN crash class** — killed structurally by `GeoJsonSafe`
  sanitization at the GeoJSON boundary.

### Open
- **Airspace as an instrument — relevance/declutter half ✅, "Timely" half ⬜
  (⏸ parked 2026-06-21).** Done: zoom-gated fills (region = outline-only),
  class-emphasised borders, altitude-aware relevance (BOLD/NORMAL/FAINT vs live
  altitude per ~500 ft bucket; Geneva-TMA recede case tested), labels hidden at
  region zoom (`AirspaceRelevance`, on-device verified via Aravis replay).
  *Remaining (K2 Timely):* vertical-proximity readout ("180 m below CTR floor") +
  trajectory look-ahead; GND/SFC floors treated as surface-reaching (no per-
  airspace terrain).
- **FAI / competition editor — partly re-validated.** Per-point editor rebuilt;
  FAI-triangle detection ✅. *Remaining:* surface FAI **points** in the panel +
  full comp-flow validation.
- **Overlay tap/select on dense overlapping clusters.** PG-spot + waypoint tap
  wired (maplibre-compose `SymbolLayer onClick`) + ground-distance snap. *Still
  open:* reliable hit-test on dense clusters — the durable fix is a real
  projection hit-test (`cameraState.projection.positionFromScreenLocation`, as
  `OffScreenPeerIndicators` uses).
- **Corridor-DEM terrain clearance** along legs (async elevation).

### Not bugs (documented so they aren't re-investigated)
- **Mt Herman PG spot invisible at region zoom** — expected MapLibre
  `iconAllowOverlap=false` collision-declutter; cached + in inventory; no safety
  impact.

---

## Infrastructure & quality

### Overlay infrastructure — the reactive principle
Spatial data (airspaces/PG-spots/weather) is cached on disk as Hilbert-indexed
mmap'd FlatBuffers — never lost, O(log N) to query. **Overlays are a reactive
projection of that cache onto the current viewport** — created on demand,
discarded out of view. Memory is bounded by viewport size, not flight distance,
so "safety-critical = keep in memory forever" is the wrong model (it causes OOM);
"safety-critical = if the pilot is near it, render it from disk every viewport
change" is the right one. Peers (≤~20) + active task are tiny/bounded and stay in
memory.

| Item | Status |
|---|---|
| **S1 reactive rendering** (`OverlayPrioritizer`, distance-decay) | ✅ implemented · ⬜ **soak gate** (11-hour IGC replay to confirm flat heap) is the acceptance criterion still owed |
| S2 emergency cleanup (last-resort shed farthest-first) | ✅ (`c0b65de`; logs a warning = treat as an S1 bug) |
| S3 airspace z-ordering (GeoJSON layers, peers topmost) | ✅ (`5696516`; dead Compose overlay code deleted `59c98e1`) |
| S4 silent action drop in MapStore | ✅ (`TernAction` marker + `Log.w` else; `914e8d3`/`ccbf1d8`) |
| **S5 gate `PerformanceDebugger.recordStateUpdate()` behind `BuildConfig.DEBUG`** | ⬜ **one-line fix** (hot path; affects glanceability in turbulence) |

**S1 soak — substantially probed (2026-06):** opt-in `probeOverlays` on
`MezullaPeerCycleTest` sampled heap + airspace/PG counts each tick across all
three real-board flights at 64×; leak regression R²≈0, peak heap 35–67 MB vs the
360 MB budget while overlay load climbed (Aravis 180→258 airspace / 272→425 PG).
To promote probe→gate: a pass/fail threshold + a real multi-hour (not 64×) run.

**Code-quality (not safety):** Q1 `Map<OverlayType,OverlayConfig>`; Q2 standardize
sealed interface; Q5 delete never-dispatched `OverlayActions`; Q6 stale comments;
Q7 `PGSpotOverlayManagerTest` coroutine-scope leak from S2. (Q3/Q4 obsolete.)
Also: stale `SAFETY/CLIMB/TACTICAL` *comments* linger in the mezulla UI/formatter
files though the view-mode enum is gone — tidy when convenient.

### Smaller follow-ups (not blocking)
- App-side **release packet** on "Forget Board" (firmware handler exists; app
  doesn't TX it) — also tracked under Epic 01 1.1.
- Peer-state reset at scenario start (stale identity-only peers linger in tests —
  cosmetic).
- ~~Peer positions logged during flight recording~~ — **now homed in Epic 05 5.2**
  (the "flight memory" sidecar: post-flight gaggle replay + incident
  reconstruction + swarm-sim fixtures).

---

## Publishing checklist

Not started — no Play Console account yet. The release build works (signed +
R8-shrunk; commit `089f1d2`; `tern-release-build` memory) but is signed with a
**throwaway dev upload keystore**. For when we actually publish:

- **Signing:** create a *real* upload key (not the dev `upload-keystore.jks`);
  enrol in **Play App Signing**; put it in `keystore.properties` (gitignored) and
  **back it up** (password manager + offline). Never publish with the dev key.
- **Artifact:** ship an **AAB** (`./gradlew bundleRelease`); sanity-check with
  bundletool; bump `versionCode`/`versionName`; confirm `applicationId` =
  `com.ternparagliding` (immutable after first publish); `targetSdk` ≥ Play min.
- **R8 correctness:** full functional pass on the **minified** build — every
  Jackson path (weather, airspace/PG/task/spatial caches, remembered-device
  restore, offline geocoder). Obfuscation stays off (`-dontobfuscate`) until those
  DTOs migrate to kotlinx.serialization. Confirm demo/replay surfaces absent.
  Consider a crash reporter.
- **Compliance:** privacy policy URL; data-safety form (location precise/
  background?, Bluetooth, network); background-location justification; content
  rating; listing assets (icon, feature graphic, screenshots, copy, category);
  ads declaration (none).
- **Pre-launch:** internal-testing track first; fix the pre-launch report crashes;
  verify on a clean device with the real upload-key signature.

> Also covered by **Epic 04 Story 4.3**. Submission content (listing copy,
> data-safety, content-rating answers) drafted in
> [play-store-listing.md](play-store-listing.md).

---

## Reference — QR pairing wire protocol

Status: ✅ COMPLETE (2026-05-27, hardware-verified). Detailed contract for the
pairing handshake (also the basis for all Meshtastic BLE comms).

**Flow:** board boots unclaimed → random token → OLED QR `tern://p?n=<node_hex>&t=<token>`
→ BLE NO_PIN → phone scans → Tern parses → BLE connect + claim → board stores
owner, switches FIXED_PIN, clears QR → app persists node id.

**BLE sequence (order matters):** scan service `6ba1b218-…eafd` → connect GATT →
request MTU 517 → discover services (after `onMtuChanged`) → find chars by
property (WRITE=ToRadio, READ=FromRadio, NOTIFY=FromNum) → read/write.

**Wire protocol** (port PRIVATE_APP = 256):

| Command | Byte | Payload | Response |
|---|---|---|---|
| Claim | `0x01` | `[token_len][token][owner_id]` | `0x00` ok · `0x01` mismatch · `0x02` already claimed |
| Query | `0x02` | (empty) | `0x00` + owner_id (also dumps OLED to serial) |
| Release | `0x03` | `[owner_id]` | `0x00` ok · `0x03` not owner |

**BLE mode:** Unclaimed NO_PIN(2) · Claimed FIXED_PIN(1) · Released NO_PIN(2).

**Implemented:** deep link (`TernParaglidingActivity.handleDeepLink`), parser
(`TernDeepLink`), BLE claim (`BlePairingService`, `PairingOrchestrator`,
`MezullaPairingCodec`), GATT UUIDs discovered (`MeshtasticGattUuids`), Settings
UI, firmware `MezullaOwnershipModule.cpp`, QR render `MezullaQrScreen.cpp`, screen
dump + decoder (`scripts/decode-mezulla-screen.py`), tests (`BlePairingTest`,
`scripts/reset-mezulla.sh`). **Remaining:** ⬜ app-side release packet (cmd 0x03)
on Forget Board.

---

## Archive

Historical context (frozen, not part of the live plan):
- [../archive/current-focus-aravis-replay-achieved.md](../archive/current-focus-aravis-replay-achieved.md) — the Aravis-replay milestone focus.
- [../archive/known-issues-pre-teardown-2026-06.md](../archive/known-issues-pre-teardown-2026-06.md) — pre-teardown product postmortems.
- [../archive/testing-bdd-suite-removed.md](../archive/testing-bdd-suite-removed.md) — why the BDD suite was removed (verification is now claim-driven; see [../claims.md](../claims.md)).
