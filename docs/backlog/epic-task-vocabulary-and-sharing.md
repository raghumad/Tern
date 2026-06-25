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
  *(**Built — Stage C, 2026-06.** The waypoint library is now the unified **Spot**
  store (USER / IMPORTED / PG_SPOT provenance); a task point references a Spot by
  `spotId` + carries only per-task features (role/cylinder/gates) + an identity
  snapshot; editing a spot flows to every task; ad-hoc map drops auto-create a USER
  spot; PG spots can be pulled into a task; and the references now **persist** —
  `spotId`/`description` were never written before, so Stage B links died on restart.
  See [../claims-pilot-validation.md](../claims-pilot-validation.md).)*
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
| **Task** | The whole planned course to fly: an ordered set of waypoints plus its rules. (Renames "Route".) | `model/Task.kt` |
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
| **Leg** | The segment between two consecutive waypoints; has a distance. | `Task.legDistances` |
| **Task type** | Open Distance / Flat Triangle / FAI Triangle. | `TaskType` |

## 2. Task-first rename — scope & sequencing

**Phase A (user-facing strings only — low risk, ship first):**
- Dock button: "Route Management" → **"Tasks"** — done; its icon is now the
  unified **waypoint flag glyph** (`nf-fa-flag`, `WaypointGlyph`), the same symbol
  the map markers, page headers, and the Settings "Waypoints" row use
  (`MapControlButtons.kt`).
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
| **Task as file** (.xctsk / .cup / .wpt) | Task detail / task-row action | `TaskIOManager` export exists; needs an Android share-intent entry point |
| **Task as QR** | Task detail (already has a QR dialog) | `TaskIOManager.generateQRCode` works |
| **Live position to peers** | Mezulla settings (near view-mode / pairing) | mesh layer exists; needs a share toggle |
| **Flight log / IGC track** | Flight / Logbook settings | replay/log state exists in `FlightDeckState`; export TBD |

Net dock after this epic: **Settings · Recenter · Tasks · Vario · Mezulla view**.

## 4. The "wind glyph" — clarification, not a redesign

The vario button is **`VarioConnectButton`** (`MapControlButtons.kt`). It has a
defined job: tap → `MapAction.ToggleVario` → scan/connect the **XC Tracer external
vario** over BLE (white = idle, amber = scanning, green = streaming). **Done
(2026-06):** the optional polish landed — the icon now reads as Bluetooth
(`Icons.Default.Bluetooth` / `BluetoothSearching` / `BluetoothConnected`), not the
old `Air` wind glyph, so it isn't mistaken for a weather control.

## Sub-decision (resolved)
Rename the **code symbols** (`Route`→`Task`, etc.) now, or defer? **Resolved
(2026-06): done.** The full code-symbol rename shipped (`b926857`, plus a
2026-06 follow-up clearing the last leftovers — `MAX_ROUTES`→`MAX_TASKS`,
`OverlayType.ROUTES`→`TASKS`, `OverlayKind.ROUTE_WAYPOINT`→`TASK_WAYPOINT`, and
the `ROUTE_*` colour / cache / name-prefix constants). Only mesh-routing code and
the `route_24` drawable asset remain by design.

## Definition of done
Glossary adopted in all user-facing copy; the generic Share button is gone and
each share capability lives in its feature's context; the vario button's purpose
is unambiguous. Backed by the same claim-driven discipline as the routes epic.
