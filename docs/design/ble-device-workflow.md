# BLE device workflow — one flow for every Bluetooth device

> **Status: DESIGN (2026-06-21).** Defines the unified pairing/connection UX and the
> self-healing connection lifecycle for *all* Tern Bluetooth devices — the XC Tracer vario,
> the Mezulla LoRa board, and anything added later. Decisions taken with the user. No code yet;
> this is the spec the implementation follows.

## Why

Today each device has its own ad-hoc path: the vario auto-scans by name and grabs the *first*
match (so at a busy launch it can connect to **someone else's** vario), and the Mezulla board
uses a hardcoded MAC with no real pairing UI. A pilot shouldn't learn two flows, and should
**never** have to babysit a connection in the air.

**North star:** the pilot adds a device **once** (ideally at home, calm). Forever after it
**connects by itself whenever it's powered and in range**, and **heals itself** through every
drop, stack hiccup, power-cycle, or range loss — *with zero taps in flight*. The only human
controls are the deliberate ones: **pause** a device, or **forget** it.

## Principles

1. **One flow for every device.** Identical add → pick → remembered. Per-device-type quirks
   (a Mezulla *claim*, a vario's FFE0/FFE1 profile) are invisible to the pilot.
2. **Self-healing, never needs intervention.** A connection that drops is the app's problem to
   fix, silently and forever — not a modal the pilot must dismiss mid-thermal. The status chip
   *reflects* reality; it never *demands* an action.
3. **Pair once, calm.** Disambiguation happens during a one-time add (signal strength + name +
   MAC), not in the air. After that the pilot never picks again.
4. **Honest status, no fake green.** "connected / linking / off / out of range" reflect the
   real link state; battery + signal are shown when known.

## The model

A persisted list of the pilot's **remembered devices**. Each remembered device:

```
RememberedDevice(
  id:          stable app id
  type:        VARIO | MEZULLA | …            (drives the driver: scan match, connect, claim?)
  displayName: "XC Tracer Mini II"            (last seen advertised name)
  mac:         "F0:24:F9:92:61:84"            (the disambiguator + reconnect target)
  claimToken:  String?                        (Mezulla only — the ownership token; null for a vario)
  paused:      Boolean                        (the one deliberate "stop trying" control)
)
```

A device is either **remembered** (in the list) or not. Remembered + not paused ⇒ the manager
perpetually works to keep it linked. That's the whole mental model.

## UX surfaces (both, per the decision)

### 1. Settings → Devices (the manager)
The home for add / list / pause / forget.

```
My Devices
──────────────────────────────────────
🪂  XC Tracer Mini II          ● connected
    vario · 🔋82% · strong          [ ▮ on ]
──────────────────────────────────────
📡  Mezulla 4a31               ◌ linking…
    buddy radio · paired            [ ▮ on ]
──────────────────────────────────────
            [  + Add a device  ]

(swipe / long-press a row → Forget)
```

Status glyphs: `●` connected · `◌` linking (scanning/connecting/healing) · `○` off (paused) ·
`⊘` out of range (tried, not found — still trying). None of these require a tap.

### 2. Map shelf chip (glance + quick toggle)
The current "Connect vario" shelf button generalises into a compact device chip per remembered
device — glance the live state, tap to pause/resume, long-press to open the Settings manager.

```
MAP SHELF:   [🪂● 82%]  [📡◌]        tap = pause/resume · long-press = manage
```

### 3. Add a device (same 3 steps for everything)
```
Add a device
   [ 🔍  Search nearby ]   any device (RSSI-sorted)
   [ ▦  Scan QR code  ]   Mezulla shortcut (select + claim in one)

— Search nearby —
Searching…   tip: hold your phone against your device
🪂 XC Tracer Mini II   ▮▮▮▮ strong  →
📡 Mezulla 4a31        ▮▮▮▯ good    →
🪂 XC Tracer Mini II   ▮▮▯▯ weak    →   (someone else's)

tap yours → connect → (claim if Mezulla) → ✓ remembered, auto-connects from now on
```

**Disambiguation** (multiple of the same type): sort by **RSSI (closest first)** — your device,
on your harness/phone, is strongest — plus name + the **MAC tail** to tell identical names apart.
The "hold your phone against your device" tip makes the closest unambiguous. QR sidesteps it
entirely for Mezulla.

## Self-healing connection lifecycle (the core)

Each remembered, non-paused device gets a **supervised connection** that lives **app-scoped**
(not tied to a Compose composition or a single screen), so rotation, navigation, and
backgrounding never tear it down. Its sole job: drive the link toward CONNECTED and keep it
there, retrying forever.

State machine (per device):
```
        ┌───────────────────────────── paused ──────────────────────────────┐
        ▼                                                                     │
   OFF ──resume/launch──► SCANNING ──found──► CONNECTING ──ok──► CONNECTED    │
        ▲                    ▲  ▲                  │                 │        │
        │                    │  └──── fail/timeout─┘                 │        │
        └──── pause ─────────┴──────────── drop / GATT error / power-cycle ◄──┘
```

Recovery rules — all automatic, no UI:
- **Drop / GATT error (incl. the GATT-133 first-connect flake):** close cleanly, rescan,
  reconnect. Backoff is **adaptive**: fast (~2 s) while recently-seen, widening to ~15–30 s if
  the device stays absent (battery), and **snapping back to fast on any sighting**. In-flight
  recovery is therefore seconds, not minutes.
- **Out of range:** stay in SCANNING (low duty cycle after the fast window); auto-connect the
  instant it reappears.
- **Device power-cycle:** identical to a drop — rescan finds it, reconnects.
- **Bluetooth adapter off:** go idle; **listen for adapter ON** and resume automatically.
- **App background / screen off (in flight the app is foreground, but):** the supervisor keeps
  running so the link survives; consider a foreground service if Android throttles it.
- **Permissions revoked:** the *only* case that can need a human — surface a single quiet
  re-grant prompt, never a crash; resume on grant.

The **pause** toggle is the one intentional stop (→ OFF). **Forget** removes the device and its
supervisor. Nothing else ever asks the pilot to act.

## Add-flow detail: the readiness gate, error taxonomy, and the no-cascade rule

The add flow must **just work**, and when it can't, say *exactly* why in plain words with one
clear fix — and **never** send the pilot on a whack-a-mole where fixing one thing reveals a new
blocker. The structural guarantee:

> **Check every precondition together, show them as one checklist, and only begin scanning when
> all are green.** A pairing *attempt* (scan / connect / claim) therefore can only fail for an
> *attempt* reason — never for a precondition reason discovered mid-way. And any corrective
> action re-evaluates the **whole** checklist, so the next screen never surprises the pilot.

### 1. The readiness gate (prevents the cascade)
Before any scan, compute **all** unmet preconditions at once and present them as a checklist
that auto-advances when everything is green:

```
Get ready to add your device
  ✅  Bluetooth access
  ⬜  Bluetooth is on            [ Turn on ]
  ⬜  Location is on             [ Turn on ]
      Android needs this to find Bluetooth devices.
      Tern never tracks where you are.

(search starts on its own once all three are ready)
```
- The list shows **everything needed up front** — fixing Bluetooth never then reveals "location
  off", because location was already on the list.
- Each item flips ✅ live as the pilot fixes it; at all-green we auto-advance to searching.
- Preconditions are only listed when they actually apply: on Android 12+ with `neverForLocation`,
  Location isn't required, so we **don't** ask for it (never demand a needless action).

### 2. Errors are friendly, unambiguous, and each has exactly one fix
Every failure is a `UserMessage(title, body, cta)` — centralised so the copy is reviewable and
testable, not scattered in the UI. The taxonomy:

| Cause | Title | Body | Fix |
|---|---|---|---|
| Bluetooth permission off | Allow Bluetooth | Tern needs Bluetooth access to find your device. | Grant access |
| Bluetooth adapter off | Turn on Bluetooth | Bluetooth is off — turn it on so Tern can connect. | Turn on |
| Location off (when required) | Turn on Location | Android needs Location on to find Bluetooth devices. Tern never tracks where you are. | Turn on location |
| No device found | Can't find your device | Make sure it's switched on, then hold your phone right next to it. | Search again |
| Connect failed | Couldn't connect | Move closer to *{name}* — we'll keep trying. | (auto-retry) |
| Board already claimed (Mezulla) | Already paired elsewhere | *{name}* is paired to another phone. Reset it (hold the button 5 s), then search again. | Search again |
| Claim didn't complete | Pairing didn't finish | Move closer to the board and try again. | Try again |

### 3. The no-cascade rule, mechanically
Every corrective action (a CTA, or "Search again" / "Try again") routes back through the
**readiness gate** — never straight into a retry of just the failed step. So after the pilot
acts, the gate re-checks permission + Bluetooth + Location as a set; a pairing attempt only ever
re-starts from an all-green gate. Two failures can't chain into a frustrating sequence because
preconditions are *all* surfaced before the first attempt, and re-validated *as a set* after any
fix.

## Connection status & event log (visibility)

Self-healing only earns trust if the pilot can *see* it working. Status is shown at three
depths, none of which ever demands an action:

1. **Glance (always-on):** the live status glyph + battery + signal on the device row and the
   map-shelf chip (`● connected · ◌ linking · ○ off · ⊘ out of range`).
2. **Last event (one line):** each device row carries its most recent transition in plain words —
   *"linked 2m ago"*, *"healed after 4s drop"*, *"out of range since 14:51"*.
3. **Event log (tap a device → detail):** a timestamped, scrollable history of connect /
   disconnect / reconnect events, each with a **reason** and, for drops, the **outage duration**
   — so a flaky vario or BLE interference is visible after the flight, and you can confirm every
   drop self-healed.

```
XC Tracer Mini II                       ● connected
F0:24:F9:92:61:84 · vario · 🔋82% · strong
──────────────────────────────────────
Connection log
  14:58:03  ● linked            (2.1 s to connect)
  14:57:55  ◌ reconnecting…     out of range
  14:57:51  ○ dropped           link lost (4 s outage)
  14:32:10  ● linked            (GATT-133 retry, healed)
  14:31:48  ◌ scanning…         app launch
──────────────────────────────────────
            [ Pause ]   [ Forget ]
```

The log is an in-memory ring buffer per device (bounded; survives the session, not persisted by
default). Every supervisor state transition appends one entry — so the log is a *byproduct* of
the state machine, not separate bookkeeping. Disconnect events specifically record:
**when, reason** (link lost / out of range / Bluetooth off / power-cycle / GATT error) **, and
the outage length** once it heals. This is the same stream the on-device self-heal smoke tests
assert against (power-cycle → a dropped+relinked pair appears with a short outage).

## Per-device specifics (folded in behind one driver interface)

A small `DeviceDriver` abstraction so the manager is device-agnostic and new devices are
drop-in:
- **scan match** — how to recognise this device in a scan (vario: name contains "tracer"/"XCT";
  Mezulla: MAC / Meshtastic service).
- **connect + subscribe** — GATT specifics (vario: FFE0/FFE1 notify; Mezulla: Meshtastic GATT).
- **claim?** — optional one-time ownership handshake (Mezulla: write claim token, read OK;
  vario: none).
- **parse** — bytes → domain (vario: `$XCTRC` → `SensorFix`; Mezulla: Meshtastic frames →
  peers). *Existing, unchanged.*

What this reuses (don't rebuild): `XcTracerBleClient` (vario transport + reconnect),
`BleConnection` / `MezullaConnectionManager` (board transport + reconnect), `BlePairingService`
(the claim handshake). The new piece is the **registry + supervisor + shared add/scan UI** that
sits above them.

## Persistence

The remembered list persists (the existing settings/prefs store). Stored per device: id, type,
displayName, mac, claimToken?, paused. On launch the manager reads the list and starts a
supervisor for each non-paused device. (This is also the K7 *"device memory"* claim:
a remembered vario MAC persists and is offered/auto-connected next launch.)

## Verification plan

- **JVM / claim-tested (no hardware):**
  - The **supervisor state machine** as a pure function: drop → SCANNING, found → CONNECTING,
    ok → CONNECTED, pause → OFF, adapter-off → OFF-until-on; adaptive backoff schedule.
  - **Disambiguation/sort**: a scan-result list sorts by RSSI, identical names split by MAC.
  - **Persistence round-trip**: remembered list saves/loads; paused honoured on launch.
  - **Event log**: each state transition appends one entry; a drop→reconnect pair records the
    reason and the outage duration; the buffer is bounded (oldest dropped).
- **On-device `[smoke]` (needs the hardware, with the pilot):**
  - Add-flow (search + QR), pick-the-right-one among two varios.
  - Self-heal: power-cycle the vario mid-stream, toggle Bluetooth, walk out of range and back —
    the link returns every time with no taps, and each drop+heal shows in the connection log.
    (Extends the already-verified vario auto-reconnect + GATT-133 self-heal.)
  - In-flight: live wind while circling, GPS hand-off under movement.

## Build phasing (proposed)

1. **✅ Registry + supervisor (JVM core) — done (2026-06-21).** `RememberedDevice` + `DeviceRegistry`
   (add/dedup-by-MAC/forget/pause/active), `DeviceCodec` (lossless persistence round-trip,
   graceful on empty/corrupt), `sortByProximity` (RSSI disambiguation), and the pure
   `ConnectionSupervisor` state machine — adaptive backoff (fast when recently seen, exponential
   while absent, capped, snap-back on sighting), self-heal through drop / GATT-fail / out-of-range
   / Bluetooth-off→on with **only automatic commands**, plus the `ConnectionLog` ring buffer
   (transition → entry with reason + outage). `DeviceWorkflowClaimsTest` (11 cases). The Android
   layer (next slices) feeds it BLE events and executes its commands; `XcTracerBleClient` is the
   first driver it wraps. *No hardware needed — done.*
2. **Add/scan UI** — the unified "Add a device" (search list + QR) and the Settings → Devices
   manager, incl. the per-device status + connection log view.
   - **✅ Flow logic + anti-cascade gate done (2026-06-21):** `PairingReadiness` (full checklist
     surfaced at once; location only when the platform needs it), `PairingFlow` (phases + the
     re-check-the-whole-gate `recheck`), and the centralised friendly `UserMessage` error
     taxonomy. `PairingFlowClaimsTest` proves the no-cascade guarantee (fixing one precondition
     never reveals a new one; an attempt only starts all-green). *No hardware.*
   - **✅ Unified picker + connection log shipped + on-device verified (2026-06-21):** ONE
     "**Add a device**" entry in Settings → Connections opens a single RSSI-sorted list of *all*
     nearby BLE devices (`BleDeviceScanner` finds varios by name + Mezulla by the Meshtastic
     service UUID). The pilot taps **their** vario (shown with signal + MAC tail) → it's remembered
     by MAC (`SettingsState.rememberedVarioMac`, persisted) and the transport (`XcTracerBleClient.
     setTarget`) connects to **only that one** — no more blind-grab-first. A Mezulla appears in the
     same list, added via its QR (claim needs the token). Each remembered device shows live status,
     a **connection log** (drop/heal with timestamps + outage), and **Forget / choose another**.
     Verified live: both the real XC-Tracer (`··C6:2F`) and Mezulla (`007_6184`) listed together;
     picking the vario remembered + linked it; `Xc-Tracer · Streaming · ● linked`.
   - **Remaining:** the readiness-gate checklist screen (logic done; perms were granted so it's not
     yet surfaced), in-app QR scan path, the map-shelf chip, and Mezulla under the same supervisor.
3. **Map shelf chip** — generalise the vario shelf button into the per-device status chip.
4. **Mezulla as the second driver** — fold the board into the same registry (claim via QR or
   list); unify its connection under the supervisor.

Slice 1 is pure-logic and needs nothing from the pilot; slices 2–4 are validated together on
the hardware.
