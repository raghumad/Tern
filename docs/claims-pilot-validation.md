# Pilot-perspective claim validation (Stage C + remediation)

> **Why this exists.** Our claims passed (reducer-level) while the app crashed on
> delete and long-press/drag were dead. Root cause: verification stopped at Redux —
> [claims.md](claims.md) even scopes out the GL surface ("the GL surface is not under
> test"). A claim is a **promise to a pilot** ("I can create a waypoint"), proven by a
> *pilot journey* (actions → outcome) judged on the five axes **and** on whether the
> experience is pleasant/intuitive. This doc validates every claim we implemented in
> that frame, and is the execution plan to make each honestly held.

## The two layers (a claim isn't "held" until both are)

- **L0 — reducer assertion** (what we had): `mapReducer(state, action)` produces the
  right state. Necessary, not sufficient — it can't see gestures or rendering.
- **L1 — pilot outcome (automatable):** drive the *real* gesture(s) and assert the
  outcome the pilot sees, plus the objective axes (**Offline**, **≤N actions /
  no forced dialog** = Frictionless, **no-crash** = Resilient) and the *measurable*
  proxies for intuitive (touch target ≥48–64 dp, haptic+visual confirmation fires,
  action is reversible). On the map this needs a **coordinate-driver** (UiAutomator),
  because the GL surface never goes idle so wait-for-still tools (`ComposeTestRule`)
  can't drive it.
- **L2 — experience quality (human rubric):** the irreducibly subjective residue —
  discoverable without instruction? feedback obvious? recoverable from mistakes?
  readable in sun / usable with gloves / feels instant? Run on-device, by a pilot.
  Lives in [ux/validation-checklist.md](ux/validation-checklist.md).

## Status legend
✅ honestly held · ◑ partial · ✗ no pilot path yet · (L0 green for all implemented claims)

## The matrix

| # | Pilot journey | Reducer claim (L0) | UI wired? | L1 honest? | L2 rubric (human) |
|---|---|---|---|---|---|
| 1 | **Create a waypoint** (long-press) | `TaskSpotModel` long-press auto-spot | ✅ `onMapLongClick` | **✅ held** (`TaskMapClaimsTest`) | long-press discoverable? marker+haptic confirm? undo a mis-drop? |
| 2 | **Build task from library** (pick) | `TaskFromLibrary` K9 | ✅ picker sheet | **✅ held** (`TaskEditorClaimsTest` — real ribbon→picker→add taps) | "add from library" findable? pick-order obvious? empty-state helpful? |
| 3 | **Add a PG spot to a task** | `TaskPgSpot` K15 | ✅ weather sheet | **✅ held** (`TaskPgAddClaimsTest` — **live PG data** on-device; skips if offline) | button discoverable / correctly absent w/o task? clear it joined? |
| 4 | **Import a comp file** | `TaskBind` K11, import-seeds-spots | ✅ task-list import | **✅ held** (`ImportCompFileClaimsTest` — **real Chelan Open** .cup/.gpx/.wpt; picker itself is L2) | import discoverable? success/failure clear? re-import refreshes not dups? |
| 5 | **Edit a spot → flows to all tasks** | `TaskMutation` edit/rename flows | ✅ editor (rename) | **✅ held** (`TaskEditorClaimsTest` — types Name, asserts flow to 2nd task) | clear edits are shared not per-task? expected model? confirmation? |
| 6 | **Edit features (role/cyl/gates), incl. clear** | `TaskMutation` gate/radius clearable | ✅ editor | **✅ held** (`TaskEditorClaimsTest` — sets then blanks radius) | numeric entry pleasant? "cleared vs default" clear? readable? |
| 7 | **Move a waypoint (move-mode)** | `TaskMutation` drag moves/cancel restores | ✅ "Move on Map" → tap | ◑ **L1 blocked** (single-tap can't be injected on the GL surface; commit-wiring proven by dispatch-order analysis) | "Move on Map" discoverable? banner instruction clear? Cancel undoes? long-press doesn't drop a stray point while moving? |
| 8 | **Delete waypoint/task — no crash, no ghost nav** | `NavStateCleanup` K14 + crash fix | ✅ editor/list (confirm) | **✅ held** (`TaskMapClaimsTest`) | confirm explains consequence? forgiving? (must cover orphaned-spot render) |
| 5/6 entry | **Tap a waypoint → select/open editor** | (interactive editing) | ✅ `TaskLayer.onClick` | ◑ **L1 blocked** (`@Ignore` — single-tap can't be injected on the GL surface; layer-click path unchanged) | clear which point is selected? target ≥48dp? |
| 9 | **Links survive a restart** | `TaskSpotModel` spotId round-trip, v0 | ✅ persistence | **✅ held** (no GL needed) | — (invisible guarantee) |
| 10 | **Resolver: live identity & stale flag** | `TaskResolver` K10 | ✅ read path | **✅ held** (`TaskEditorClaimsTest` — edit flows, deleted spot → flyable + stale, no crash) | stale amber "!" noticeable mid-flight? |
| 11 | **Map never crashes on bad data (P2)** | `GeoJsonSafe` invariant | ✅ structural | **✅ held** (no GL needed) | — (structural) |

## Honest scorecard (updated 2026-06)
- **L0 green:** 11/11.
- **L1 honestly held:** **#1, #2, #3, #4, #5, #6, #8, #9, #10, #11** — 10 journeys.
  - *GL surface* (`TaskMapClaimsTest`): #1 long-press create, #8 delete→no-crash.
  - *Sheets/editor* (`TaskEditorClaimsTest`, UiAutomator over real Compose): #2 library
    pick, #5 rename-flows, #6 set+clear radius, #10 resolver stale + orphan render.
  - *Live data* (`TaskPgAddClaimsTest`): #3 add a **live** PG spot on-device (skips if offline).
  - *Real comp file* (`ImportCompFileClaimsTest`, JVM): #4 parse + merge the **Ozone
    Chelan Open 2026** waypoint set across .cup/.gpx/.wpt, no-dup on re-import. *(This
    surfaced and fixed a real bug — the CompeGPS degree byte decodes to U+FFFD, which the
    old `compeCoord` couldn't parse → 0 waypoints.)*
  - *GL-free guarantees*: #9 restart round-trip, #11 render-safety invariant.
- **Still L1-blocked:** **#7 move-mode commit** and **tap-select** — both need a GL
  *single tap*, which can't be injected on this device (`@Ignore`d; see lesson below).
- **L1 reliability lesson (updated 2026-06):** the live GL surface accepts the
  **long-press swipe** reliably (`input swipe x y x y 800` — `onLongPress` fires
  mid-gesture), but on the Ulefone this session **no synthetic single tap** reaches it
  (`device.click`, `input tap`, short zero-/tiny-distance swipes, explicit DOWN/UP all
  fail to fire `onSingleTapConfirmed`). So the tap-dependent L1s (tap-select; move-mode
  commit) are `@Ignore`d with that reason rather than left red — their correctness is
  covered by **L0** (`TaskMutationClaimsTest`) + the MapLibre **click-dispatch order**
  (verified: `onMapClick` fires first and consumes *only* in move-mode, so it commits the
  move and leaves tap-select intact) + **L2** (manual). Several journeys (#3 PG-add needs
  live data, #4 import uses the system file picker) are **inherently L2-manual** too.

## Execution order
1. **Re-tag** [claims.md](claims.md) K6 with L0/L1/L2; mark #9 & #11 held. *(docs)*
2. **Coordinate-driver** (UiAutomator + store-reader) — the cruder camera that can drive the live map. *(one-time infra)*
3. **#1 create-waypoint** — first GL-surface L1 template; verify on-device. *(done)*
4. **#7 reposition** — built as **move-mode** (tap-select → "Move on Map" → tap new spot),
   chosen over press-and-hold drag; reuses the `StartWaypointDrag` machine. L1 blocked on
   tap injection (above). *(done)*
5. **Remaining L1** tests: #2, #5, #6, #10 (sheet/editor via UiAutomator + `testTagsAsResourceId`),
   #3 (live PG data on-device), #4 (real comp file, JVM). **All done (2026-06).** #8 already held.
6. **Revive [ux/validation-checklist.md](ux/validation-checklist.md)** with the per-journey L2 rubric (Task vocabulary).

## Boundary (set expectations honestly)
- L1 proves *correctness + the measurable parts of intuitive + no-crash, forever*.
- L2 (human) is the only proof of *pleasant / discoverable-to-a-novice / feels-right-in-the-hand* — it cannot be automated and must not be faked with a reducer assertion.
- "Claim held" = L0 ∧ L1 ∧ L2.
