# Epic: Task-first vocabulary + contextual sharing

> **Status: PLANNED (2026-06-15).** Two product decisions taken with the user,
> recasting the route/share UX. Extends — does not replace —
> [epic-routes-production.md](epic-routes-production.md) (this is the naming +
> sharing layer; that epic remains the "make routes actually work" layer).
> Theme 3's "Share (QR / Android intent)" line is **superseded** by §3 below.

> **Update (2026-06-15):** the full code-symbol rename is **DONE** (commit
> `b926857`) — "route" is gone from the codebase (model, Redux, overlays, utils,
> UI, tests; package `overlay.route` → `overlay.task`). Mesh-routing code and the
> `route_24` drawable asset were deliberately left. The `model/Route.kt`
> references below now read `model/Task.kt`.

## Conceptual model (the user's framing)

- **Waypoints are the primary thing people define.** A **Task is just an ordered
  sequence of waypoints** plus per-waypoint details (cylinder radius, role, time
  gates). Tasks reference/order waypoints; the waypoint is the unit.
  *(Today waypoints are still owned inline by a Task. A shared waypoint
  library that tasks draw from — cf. the planner "LIBRARY" tab — is the natural
  next step, not yet built.)*
- **The start gate defines how weather is shown along the task.** The SSS
  open-time anchors the ETA timeline, so the per-waypoint forecast is read at the
  time the pilot is expected to *be there* (start + cumulative leg time), not
  "now". Hooks already exist (`waypointEtas`, `FetchWeatherForTask`); the
  start-gate anchor is the missing input. *(Design note — not yet wired.)*

## The two decisions

1. **Task-first.** This is a competition / XC tool. "Route" becomes **"Task"**
   throughout the user-facing product, and task parameters (speed section,
   cylinders, time gates) are always first-class — not hidden behind a mode.
   **(Done.)**
2. **No generic "Share" button.** A single "Share" affordance is confusing
   because it conflates four unrelated things. All four capabilities stay, but
   each moves to the context (the "respective settings") it belongs to. The
   dock loses its Share button.

## 1. Glossary (the canonical vocabulary)

| Term | Definition | Code today |
|---|---|---|
| **Task** | The whole planned course to fly: an ordered set of waypoints plus its rules. (Renames "Route".) | `model/Route.kt` |
| **Waypoint** | A single point that makes up a task. | `Waypoint` |
| **Role** | What a waypoint does in the task. | `LocationType` |
| **— Takeoff / Launch** | Where you launch. | `LAUNCH` |
| **— SSS** | Start of Speed Section — the start gate. | `SSS` |
| **— Turnpoint** | A point you must tag mid-task. | `TURNPOINT` |
| **— ESS** | End of Speed Section — where the clock stops. | `ESS` |
| **— Goal** | The finish. | `GOAL` |
| **— Landing** | Designated LZ. | `LANDING` |
| **Cylinder** | The circular control zone around a waypoint; you *tag* a waypoint by entering its cylinder. Defined by **radius**. | `Waypoint.radius` |
| **Speed Section** | The timed/scored portion, SSS → ESS. | derived from roles |
| **Time Gate** | The open/close window during which a waypoint's cylinder is active (e.g. the start gate). | `Waypoint.openTime/closeTime` |
| **Leg** | The segment between two consecutive waypoints; has a distance. | `Route.legDistances` |
| **Task type** | Open Distance / Flat Triangle / FAI Triangle. | `RouteType` |

## 2. Task-first rename — scope & sequencing

**Phase A (user-facing strings only — low risk, ship first):**
- Dock button: "Route Management" → **"Tasks"** (`MapControlButtons.kt:106`).
- `RouteListScreen`: title "Routes" → "Tasks"; "New Route N" → "Task N";
  empty-state copy; "WPs" → "pts". Also clean the leftover dev-comment cruft +
  wrong rename icon (`RouteListScreen.kt:270-280`).
- `RouteDetailPanel`: "Route Planner" → "Task"; tab labels reviewed.
- `EditWaypointScreen`: already uses "Task Parameters" — keep; ensure role
  labels match the glossary (Takeoff/SSS/Turnpoint/ESS/Goal/Landing).

**Phase B (code symbols — deferred, bigger churn):**
- Renaming `Route`/`Waypoint`/`RouteListScreen`/`MapAction.*Route*` etc. ripples
  through Redux, cache, IO, and tests. Do this only after Phase A is settled and
  the routes-production epic stabilises, to avoid a giant rename colliding with
  feature work. **Open sub-decision** — see below.

## 3. Contextual sharing (replaces the generic Share button)

Remove `ShareButton` from the dock, delete `showShareSheet` + the dead
`ShareSheet` mock (`BottomSheetContent.kt:55` — currently five rows with **no
onClick**). Redistribute its four intents:

| Capability | New home | Status of plumbing |
|---|---|---|
| **Task as file** (.xctsk / .cup / .wpt) | Task detail / task-row action | `RouteIOManager` export exists; needs an Android share-intent entry point |
| **Task as QR** | Task detail (already has a QR dialog) | `RouteIOManager.generateQRCode` works |
| **Live position to peers** | Mezulla settings (near view-mode / pairing) | mesh layer exists; needs a share toggle |
| **Flight log / IGC track** | Flight / Logbook settings | replay/log state exists in `FlightDeckState`; export TBD |

Net dock after this epic: **Settings · Recenter · Tasks · Vario · Mezulla view**.

## 4. The "wind glyph" — clarification, not a redesign

The wind-lines icon is **`VarioConnectButton`** (`MapControlButtons.kt:94`,
`Icons.Default.Air`). It already has a defined job: tap → `MapAction.ToggleVario`
→ scan/connect the **XC Tracer external vario** over BLE (white = idle, amber =
scanning, green = streaming). Nothing to build. Optional polish: switch the icon
to read more as "sensor/Bluetooth" than "wind" so it isn't mistaken for a
weather control. Low priority.

## Open sub-decision (blocks Phase B only)
Rename the **code symbols** (`Route`→`Task`, etc.) now, or keep the internal
names and only rename user-facing strings? Recommendation: **strings-first**,
defer the symbol rename until routes-production stabilises.

## Definition of done
Glossary adopted in all user-facing copy; the generic Share button is gone and
each share capability lives in its feature's context; the vario button's purpose
is unambiguous. Backed by the same claim-driven discipline as the routes epic.
