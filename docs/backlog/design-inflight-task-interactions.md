# Design brainstorm: in-flight task interactions

> **Status: BRAINSTORM (2026-06-15) — not approved, not started.** This is for a
> round of discussion before any implementation. Mockups are annotations over
> *real* app screenshots (Bir Billing bench replay) so we can feel the layout.

## What this is about

We have the *passive* half of task navigation working: a task auto-advances its
active waypoint on cylinder entry, the active waypoint is highlighted on-map, and
an off-screen chip points to it with name/description + distance + required glide.

This doc is about the *interactive* half — what the pilot can **do** with a task
**while flying it**, and how that's presented without compromising flight safety.

## Constraints (these drive everything)

In flight the pilot is one-handed (the other is on the brakes), often gloved, in
glare and turbulence, and must keep eyes **outside**. So:

- **Glance beats touch.** The common case ("where am I going, can I make it?")
  must be answered without touching the screen.
- **Big, sparse targets.** Few controls, large hit areas, high contrast.
- **No destructive surprises.** Anything that changes task state should be
  obvious and ideally reversible; confirm only when truly destructive.
- **Feedback on tag.** When a cylinder is tagged, the pilot must *know* (haptic /
  sound / flash) — they can't be staring at the screen to catch it.

## Today (what we build on)

- Auto-advance on cylinder entry (`TaskNavigator` + `TaskProgressOverlay`).
- On-map active-waypoint highlight (`TaskLayer`).
- Off-screen direction chip (`OffScreenWaypointIndicator`).
- **Gap:** the map has **no tap / long-press wiring at all** — tap-to-select a
  waypoint was never connected. That's the foundation for everything touch-based.

## The interaction inventory (proposed)

Three tiers by how often / deliberately they're used:

### A. Glance — zero touch (the 95% case)
On-map highlight + off-screen chip (done) **+ a persistent "Next waypoint" card**
so the answer is always on-screen even when the WP isn't. Candidate content:
name/description, distance, ETA, required glide, progress (2 of 5).

![Next-waypoint card mockup](assets/inflight/mockup-1-next-card.png)

### B. Light touch — occasional overrides
- **Skip / advance** the active waypoint manually (auto-tag missed, or you choose
  to skip a turnpoint). A button on the card.
- **Tap a waypoint on the map → "Go to"** — retarget out of sequence (bailing to
  goal, re-flying a leg). Tap opens a tiny menu rather than retargeting instantly
  (avoids accidental retarget in turbulence).

![Tap-a-waypoint mockup](assets/inflight/mockup-2-tap-waypoint.png)

### C. Deliberate — rare
- Reset / restart task progress; toggle **manual vs auto** advance (comp pilots
  may want manual control around the start gate). Lives behind the task panel or
  a long-press, confirmed.

## Open questions (the brainstorm)

1. **Persistent card — yes/no, and where?** The deck already has the vario HUD
   (bottom), compass (top-right), control dock (right). Does a "Next" card earn
   its space, or is the off-screen chip + on-map highlight enough? If yes — left
   edge? a thin top strip? merged into the vario HUD?
2. **Tap-to-retarget: instant or menu?** In-flight safety says menu ("Go to /
   Info"); fewer taps says instant. Lean: menu.
3. **Skip vs back.** Do we need "revert to previous waypoint" too, or only
   forward-skip? (Reverting matters if an auto-tag fires by mistake.)
4. **Manual mode.** Should auto-advance be defeatable? Most relevant at the start
   gate (you cross the SSS cylinder many times before the gate opens).
5. **Tag feedback.** Haptic buzz + brief on-screen flash on tag? Sound optional
   (varios already beep). This is arguably the highest-value, lowest-cost item.
6. **Card tap action.** Recenter on the active WP? Open its info? Cycle content?
7. **Glove/turbulence target size & placement** vs the existing HUD elements —
   anything that needs to move to make room?

## Tentative phased plan (sequence, not commitment)

- **Phase 0 — Foundation: map tap/long-press → waypoint hit-test → selection.**
  Nothing touch-based works without it; it's also reusable for editing. *(Prereq.)*
- **Phase 1 — Glance + feedback:** the "Next" card (Q1) and tag feedback (Q5).
  Highest safety value, no destructive actions.
- **Phase 2 — Overrides:** skip/advance (Q3) and tap-to-Go-to (Q2).
- **Phase 3 — Deliberate:** reset/restart + manual mode (Q4).

Each phase backed by a claim-driven test (replay a task, assert the pilot-visible
outcome), same as `TaskNavClaimsTest`.

## For discussion
Pick the card direction (Q1) and the tap model (Q2) first — they shape Phases 1–2.
Everything else can follow. Nothing here is built yet.
