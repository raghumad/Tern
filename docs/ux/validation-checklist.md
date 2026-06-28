# UX validation checklist — pilot-perspective (L2)

This is the **human gate** for the claims: the part no automated test can prove —
is each pilot journey *correct, and pleasant/intuitive*? Run it on a real device
(ideally by a pilot). It pairs with the automated layers — see
[../claims-pilot-validation.md](../claims-pilot-validation.md) (L0 reducer / **L1**
automated pilot-outcome / **L2** this doc).

**How to use:** for each journey do the actions, confirm *Should happen*, then judge
the **Quality** line — the heuristics that make it "intuitive": **Discoverable**
(could a first-time pilot do it without being told?), **Feedback** (clear visual +
haptic confirmation?), **Recoverable** (easy to undo/fix a mistake?), **Readable**
(legible in sunlight, hittable with gloves / in turbulence — targets ≥48 dp?),
**Instant** (no lag?). Mark ✅/⚠️/❌ and note what felt off.

> `[L1 auto]` = correctness is also covered by an automated test — on-device via
> UiAutomator (`TaskMapClaimsTest` for the GL map, `TaskEditorClaimsTest` for the
> sheets/editor, `TaskPgAddClaimsTest` for live PG data) or JVM with real comp data
> (`ImportCompFileClaimsTest`). You're judging *quality* on those; the rest are
> human-only — no automated proof exists, so this checklist *is* the proof.

## Map & task journeys

### 1. Create a waypoint  `[L1 auto: long-press creates a waypoint + USER spot]`
- **Do:** long-press an empty spot on the map.
- **Should happen:** a waypoint drops there; it appears in the Waypoints library.
- **Quality:** Discoverable (would you guess long-press?) · Feedback (marker + haptic?) · Recoverable (easy delete/undo?) · Instant?

### 2. Build a task from the library  `[L1 auto: ribbon→picker→add appends the picked spot]`
- **Do:** open the task ribbon → "Add from library" → tick spots → "Add N to task".
- **Should happen:** the picked spots join the task in pick order.
- **Quality:** is "Add from library" findable? is pick-order obvious? helpful empty-state when the library is empty?

### 3. Add a PG spot to a task  `[L1 auto: live PG spot → "Add to task" joins it as PG_SPOT]`
- **Do:** with a task selected, tap a PG spot → weather sheet → "Add to task".
- **Should happen:** the PG spot joins the task and appears in the library (once, even if re-added).
- **Quality:** is the button discoverable, and correctly *absent* when no task is selected? clear it joined?
- *(L1 uses live data on-device and skips when offline; the GL tap on the spot itself is L2 — see the tap-injection note.)*

### 4. Import a comp file  `[L1 auto: real Chelan Open .cup/.gpx/.wpt parse + merge, no-dup]`  *(the file picker itself is human-only — system UI)*
- **Do:** Tasks → import a `.cup` / `.wpt` / `.gpx` (waypoints) and a `.xctsk` (task).
- **Should happen:** spots appear in the library; an imported task binds its points to them by code; a re-import refreshes (no duplicates); a malformed file is rejected with a message, no crash.
- **Quality:** is import discoverable? clear success/failure feedback?

### 5. Edit a spot's identity (rename) → flows everywhere  `[L1 auto: type Name → flows to the 2nd task]`
- **Do:** tap a task waypoint → editor → change its Name.
- **Should happen:** the name updates here **and** in any other task using the same spot; it survives a restart.
- **Quality:** is it clear the edit is *shared* (the spot), not per-task? confirmation? does that match your mental model?

### 6. Edit task features (role / cylinder / gates), incl. clearing  `[L1 auto: set then blank the radius]`
- **Do:** editor → set a role, a radius, a time gate; then **blank** the gate and the radius.
- **Should happen:** values stick per-task (same spot can differ across tasks); **blanking clears** the gate; blank radius → default; no literal `TaskConstants…` text in the radius field.
- **Quality:** numeric entry pleasant (no cursor jank)? is "cleared vs default" understandable? fields readable in sun?

### 7. Move a waypoint (reposition — move-mode)  *(human-only — single-tap can't be auto-injected on the GL surface)*
- **Do:** open the waypoint's identity (Workflow A) — from the **library** (pencil), the **map** (tap → weather sheet → "Edit waypoint"), or a task point's editor ("Edit waypoint…") — then **"Move on Map"**; the editor steps aside and a banner appears; **tap the new spot**. (Or **Cancel** in the banner.)
- **Should happen:** the waypoint jumps to the tapped spot; because position is *identity*, the new position flows to **every** task using it; **Cancel** leaves it where it was; a long-press doesn't drop a stray new waypoint while moving.
- **Quality:** is "Move on Map" discoverable? banner instruction clear? Cancel obviously undoes? feels instant?
- *Move-mode chosen over press-and-hold drag (felt wrong on-device). Position is now an identity edit (Workflow A) so it correctly affects all tasks. Logic proven by `TaskMutationClaimsTest`; commit wiring by click-dispatch-order analysis.*

### 7b. Reorder a task's waypoints  *(human-only — list drag)*
- **Do:** Tasks → a task's **pencil** (Edit Task) → **drag a row's handle (≡)** up/down.
- **Should happen:** the point moves to the released slot, the task line + leg order update, and it persists.
- **Quality:** is the handle obviously grabbable? does the row follow the finger? is the drop slot predictable?

### 7c. Edit a waypoint's identity  `[L1 auto: rename flows to all tasks]`
- **Do:** Waypoints library → a row's **pencil** (or map tap → "Edit waypoint") → change Name / Code / Elevation.
- **Should happen:** the change applies to this waypoint **everywhere** it's used; the "applies everywhere" cue is visible; survives a restart.
- **Quality:** is it clear identity is shared (vs per-task features)? are fields readable; is Delete visible and Done reachable (not obscured)?

### 8. Delete a waypoint / task  `[L1 auto: delete does not crash the map]`
- **Do:** delete a waypoint (editor), and delete a task (task list); delete the one you're navigating.
- **Should happen:** a confirm dialog gates it; **no crash**; nav (rosette/ribbon) stops pointing at the deleted point; a deleted *library* spot leaves its task points flyable, flagged amber "!".
- **Quality:** does the confirm explain the consequence? is it forgiving?

### 9. Links survive a restart  `[L1 auto: spotId round-trips]`
- **Do:** build a task, force-quit, relaunch (airplane mode).
- **Should happen:** every point still resolves to its spot with correct identity, offline.
- **Quality:** invisible when it works — you're checking nothing silently reset.

### 10. Live identity & stale flag (resolver)  `[L1 auto: edit flows; deleted spot → flyable + stale, no crash]`
- **Do:** edit a spot used by two tasks; then delete that spot from the library.
- **Should happen:** both tasks show the new identity; after deletion the points fly from their last-known position, flagged amber "!".
- **Quality:** is the stale "!" noticeable and understandable mid-flight?

### 11. The map never crashes on bad data  `[L1 auto + structural invariant]`
- Covered by `GeoJsonSafe` (no overlay can emit a non-finite value) + the delete L1 test. No manual step; listed so the guarantee is visible.

## Cross-cutting (judge throughout)
- **Offline:** with the network off, every journey above still works (online is prefetch only).
- **Touch targets:** waypoints/markers/buttons are hittable with gloves / in turbulence (≥48 dp).
- **Sunlight legibility:** labels, the amber "!", role colours readable in direct sun.
- **No blocking modals:** nothing covers the map with a "no connection" wall.
