# Current focus: LoRa bring-up (single-board phase)

Working toward: [Epic 01](epic-01-peer-awareness-and-sos.md)

## What this is

The end-goal epic describes the full pilot-facing feature. **This file
describes what we're actually doing right now** — the bare-minimum
scaffolding we need before any pilot-visible LoRa work makes sense.

When this focus area completes, update or replace this file with the
next focus. Git history is the record of how focus has moved over time.

Only one `current-focus.md` exists at a time.

## What we have to work with

- **One LilyGo T3 board** with built-in OLED. No second board yet.
- **Phone** running Tern on the `mezulla` branch.
- Anything that genuinely needs two physical boards (e.g. a real radio
  link test) is deferred to the next focus once a second board arrives,
  or we use a host-side simulator / phone-side mock peer / board loopback.
  We don't block this focus on that.

## Milestones in this focus

In strict order — Milestone 2 starts after Milestone 1 is done.

### Milestone 1 — One LilyGo T3 boots cleanly; OLED and serial work

Flash the board with a minimal firmware. The OLED renders deterministic
content; the serial console is usable for debugging and accepts a known
command.

**Definition of done.** Every item below verified on the actual board,
with artifacts saved in this folder. *Done is not "tests passed" — it
is "the pilot has watched this happen with his own eyes, repeatably."*

- Cold-boot the board 5 times in a row from a power cycle. Each boot
  finishes within a known time budget (record what it actually is), the
  OLED renders the test frame each time, and no run shows a boot loop
  or panic on the serial console.
- The serial console comes up at a known baud (115200 or whichever),
  prints a parseable boot log, and accepts at least one debug command
  (e.g. `ping` → `pong`, or `info` → board ID + firmware build hash).
- The OLED holds a stable, deterministic frame for at least 5 minutes
  with no artifacts, flicker, or driver crash.
- Re-flashing from the build pipeline reproduces the same state — i.e.
  the build is repeatable from a clean checkout.

**Tests / evidence.**

- A photo or short video of the OLED rendering the test frame.
- The captured serial boot log saved as `current-focus-notes.md` (or a
  sibling file in this folder), including timestamps and the cold-boot
  duration measured across the 5 cycles.
- A note in that file recording the firmware version / build hash that
  produced this state.

### Milestone 2 — Phone ↔ board byte channel works (BLE, always-on)

Tern on Android (mezulla branch) maintains a BLE link to the LilyGo
board. The connection is **always-on**: while the app is running, if
the board is powered on and in range, the phone is connected to it.
No "connect" button, no manual reconnect. The pilot never thinks about
the link unless something is wrong.

**Transport: BLE.** USB is not in the picture — the cable is friction
the pilot won't accept in flight.

**Behavior when the board is off / out of range:**

- The app keeps working normally. No errors, no spinner, no blocking
  state.
- A discreet, non-blocking notification surfaces (in-app, plus
  optionally an Android system notification) — something like "LoRa
  board off — turn it on to use mesh features." Phrased as a friendly
  reminder, not an error.
- The phone keeps quietly scanning. The moment the board powers on
  (even mid-flight), Tern connects to it automatically and the
  notification clears.

**Definition of done.** Every item verified on real hardware with the
real `mezulla` branch of Tern. Artifacts saved.

- **Round-trip echo.** Phone sends a known test pattern (e.g. a 16-byte
  payload with a checksum). Board echoes the exact bytes back. Phone
  confirms bit-exact match. Repeat 10 times in a row; record the round-
  trip latency.
- **Board-to-phone push.** Board sends a "hello" message at a fixed
  cadence (say once per second). Phone receives them in order, no drops
  over a 1-minute window, and they show up in `logcat`.
- **Disconnect resilience.** Pull board power mid-session. The phone
  surfaces a "board off" state cleanly — no crash, no error modal, no
  spinner hang. The rest of the app continues to work normally.
- **Auto-reconnect.** Restore board power. Phone reconnects on its own
  within a short window (target: under 5 seconds; measure and record
  what we actually get) with no user action.
- **Cold start with board off.** Launch the app with the board off.
  The full app works normally. The discreet "board off" notification
  appears. Then turn the board on — phone connects automatically and
  the notification clears.
- **Cold start with no board ever paired.** Launch the app on a phone
  that has never seen a board. Full app works as if LoRa support didn't
  exist. No notification, no warnings — nothing surfaced.

**Tests / evidence.**

- Captured `logcat` excerpt showing the 10-echo round-trip with measured
  latencies.
- A 60–90 second screen recording (phone) covering: cold start with
  board off → notification appears → board turned on → auto-connect →
  notification clears → board power pulled → disconnect surfaces →
  board powered back on → auto-reconnect.
- A note in `current-focus-notes.md` recording: round-trip latency
  stats, time-to-reconnect after the board comes back, anything
  surprising, any BLE quirks encountered.

## Out of scope for this focus

- Real two-board LoRa link (need a second board; deferred).
- Any pilot-visible feature — no position display, no peer markers, no
  SOS, no pairing UX polish, no OLED status formatting beyond "the
  display works." Each of those gets its own focus once this foundation
  is solid.
- Production-grade firmware. We're building enough to learn from. Cleanup
  comes later.

## Open questions to resolve during this focus

- **Meshtastic firmware vs roll-your-own** at the board level. Start with
  Meshtastic to see what we get for free; defer the long-term decision
  until we know what its constraints actually cost us at the airtime
  and message-format layer.
- **Pairing model.** For this focus we can hardcode/auto-discover the
  dev board (no pairing UX yet). But before this focus closes, we should
  sketch how a real pilot pairs with their own board the first time —
  even if implementation slides to a later focus.
- **BLE scanning while the app is foregrounded vs backgrounded.** Pilots
  typically have the app in the foreground in flight, so always-scanning
  when foregrounded is fine. Background behavior can be cheaper. Decide
  the policy before this closes.
- **Two-board strategy for the next focus.** Once this is done, the next
  focus involves two boards. Options to weigh then: buy a second board,
  build a host-side LoRa simulator, or rely on board loopback for early
  tests. No need to decide now — just don't forget the decision is
  coming.

## When this focus completes

Update or replace this file. Likely next focus: "broadcast a position
from one board over LoRa and see it on another (or simulated) peer's
map in Tern."
