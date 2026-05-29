# Epic 04: First-time pilot onboarding — the 7-step brochure

> This epic exists because Epic 01 (buddy mesh) won't matter if a pilot
> can't get started in seven minutes with a coffee. The mesh can be
> technically perfect and still fail commercially / socially if Tonio
> and his three buddies can't pair their boards before tomorrow's flight.

## Why this matters

A paragliding buddy group bought four Mezulla devices and wants to fly
together tomorrow. They open the box, plug in their phones, and read
the brochure. If the brochure has a Linux laptop in it, we lost.

Tern + Mezulla are technically capable of buddy mesh today (Epic 01
core is working). What's missing is the onboarding rough edges that
separate "an engineer can make this work" from "a paragliding group
can make this work."

## The north star — the brochure we want to ship

```
Tern + Mezulla — getting started

1. Charge your Mezulla until the LED stops blinking (~1 hour)
2. Install Tern on your phone: https://tern.ternparagliding.com/get
3. Turn the Mezulla on (long-press the button)
4. Point your phone camera at the QR code on the Mezulla's screen
5. Tap the notification "Open in Tern"
6. Wait ~30 seconds for "Connected ✓"
7. Done. Your flying buddies appear on the map when you're within
   LoRa range.

Need help? Settings → Mezulla shows your board's status (battery,
link, peers).
```

Seven steps. No laptops. No jargon. No CLI. The pilot does these
once, at home or in the parking lot, then flies.

## Acceptance criterion

A pilot with no engineering background, given only the seven-step
brochure and a fresh Mezulla in its box, can be in the "Connected ✓"
state within 10 minutes of opening the package.

Verified by handing the brochure + a sealed Mezulla to a non-engineer
paragliding friend and watching. If they need to ask anything not on
the brochure, the brochure (or the product) is wrong.

## Out of scope

- Hardware sourcing / packaging / charging logistics — assumed solved
  by whoever ships the Mezulla. Epic is about the software UX from
  unbox onward.
- Buddy mesh feature work — that's Epic 01. This epic assumes it works.
- Spedmo / social layer — that's Epic 03.
- iOS — Android only (per [[project-tern-platform-focus]]).

## Stories

### Story 4.1: Boards ship pre-flashed and pre-configured
Status: todo

Mezulla devices arrive already running Tern's firmware build with a
sensible default LoRa region (configurable per shipment region, or
auto-set via Story 4.2). Pilot never touches `pio`, `esptool`, or
`meshtastic --set`.

What done looks like:
- Boards ship from raghumad with the firmware version named on the
  brochure
- Region is set during provisioning to match the shipment destination
- A pilot opening the box only needs to charge + turn on

### Story 4.2: Auto LoRa region from phone GPS on first pair
Status: todo

If a board's `lora.region` is `UNSET` when the phone first pairs with
it, Tern looks up the regulatory region from the phone's GPS (or IP
geo fallback) and pushes the correct region via Meshtastic admin.
Pilot never thinks about MHz.

What done looks like:
- GPS coordinates → ITU region mapping (US, EU_868, IN, AU_915, etc.)
- Push happens silently on first pair if region is UNSET
- Pilot moves country → next pair auto-updates region
- Wrong region means illegal transmission; the mapping must be right
- Settings shows the current region with a "change" affordance

### Story 4.3: Tern in Play Store
Status: todo

Pilots install Tern from the Play Store, not from an APK link they
sideload. Removes "enable Unknown Sources," removes adb dependency,
adds auto-update.

What done looks like:
- Privacy policy + data safety form filled
- Listing copy + screenshots
- Internal testing track works; closed beta with the user's friends
- Eventual open release

### Story 4.4: Pairing UX handles bond/screen/retry automatically
Status: todo

The current pairing flow has hidden preconditions: the screen must
be on (Android 12 BLE scan throttling), stale bonds must be cleared,
the retry pattern must be just right. None of this is on the
brochure, so all of it must be invisible.

What done looks like:
- Tern wakes the screen at the start of pairing if it's off
- If a stale bond exists for this MAC, Tern clears it before scanning
- If pairing fails with a known recoverable reason (bond mismatch,
  ALREADY_STARTED scan, etc.), Tern auto-retries with backoff
- Failure messages are pilot-readable: "Try moving the phone closer
  to the board" rather than "GATT status 133"

### Story 4.5: Eliminate the "Bluetooth pairing PIN" popup
Status: todo

Android sometimes shows a pairing-PIN dialog during BLE handshake
even when the board is in NO_PIN mode (stale bonds, timing, GMS
Fast Pair interception). The pilot doesn't know what to do.

What done looks like:
- A clean pair from a clean phone never shows the PIN dialog
- If it appears, Tern detects it and either auto-dismisses or shows
  an in-app instruction overlay ("type 123456 if asked" — or removes
  the FIXED_PIN fallback in firmware entirely)

### Story 4.6: Concurrent multi-pilot pairing
Status: todo

When four buddies sit at the parking lot pairing their boards at the
same time, all four phones see all four boards' BLE advertisements.
Each phone must pair only with its own board (the one in its QR).

What done looks like:
- Concurrent pairing of 4+ pilots in BLE range of each other all
  succeed
- No phone accidentally claims a board that belongs to a different
  pilot
- Pairing time per pilot doesn't degrade with crowd size

### Story 4.7: OTA firmware update from Tern
Status: todo

Pilots never plug their board into a laptop after unboxing. When a
new firmware is released, Tern downloads it (when on WiFi / cellular)
and pushes the update to the board over BLE or via the board's WiFi
(Meshtastic's admin OTA path).

What done looks like:
- Tern checks for new firmware on app launch (when online)
- On a new version, notifies the pilot ("Mezulla update available,
  takes ~3 minutes, do it now?")
- Update happens via either BLE OTA or the board's WiFi-pull path
  (whichever Meshtastic upstream supports best for this hardware)
- Update is resumable on disconnect; board never bricks
- Pilot can defer or cancel; flight functionality keeps working in
  the meantime
- Update history visible in Settings → Mezulla → About

Risks to address:
- Bricking risk if power dies mid-flash → use the dual-bank OTA
  partition layout from Meshtastic
- Bandwidth — firmware is ~2MB, doable on cellular but should be
  WiFi-preferred
- Trust: signed firmware images only

### Story 4.8: Settings → Mezulla shows status at a glance
Status: todo

The pilot opens Settings, sees one screen with everything they need
to trust the board is working: link state (UP/DOWN), battery, last
beacon, peer count, firmware version, "update available" if any.

What done looks like:
- Connection icon visible from the main map view (corner badge)
- Tapping it opens Settings → Mezulla directly
- Status fields all populated from Redux (data already flows;
  this is a rendering story per the earlier "Board status audit")
- Error states are recoverable with one tap ("Forget board" /
  "Re-pair")

## What's NOT in this epic

These are real but live elsewhere:

- **The duplicate-BleConnection race** (open bug from 2026-05-27
  Aravis attempt) — that's a Story 1.1 robustness fix, not
  onboarding UX. Goes in [[epic-01-peer-awareness-and-sos]] under
  Story 1.1.
- **MEZULLA_TEST_BUILD flag** — that's our test infrastructure,
  never shipped to pilots.

## Order of attack

The brochure ships when **all eight stories are done**. Suggested order:

1. **4.4 + 4.5 + 4.6** (pairing UX robustness) — these are the
   reason a pilot fails today. Cheapest wins first.
2. **4.8** (status visibility) — gives pilots confidence the rest
   is working. Selector + UI plumbing already half-done per
   "Audit board status in Redux" findings.
3. **4.2** (auto-region from GPS) — removes one of the cruelest
   CLI steps from the current brochure.
4. **4.7** (OTA firmware) — unblocks Story 4.1; once OTA works,
   pre-flashing matters less because pilots can always update.
5. **4.1** (pre-flashed shipping) — depends on hardware fulfillment
   reality; coordinate with whoever ships the Mezulla.
6. **4.3** (Play Store) — release-engineering work, can run in
   parallel with everything else.

## Related

- [[epic-01-peer-awareness-and-sos]] — the feature this epic exists
  to make accessible
- [[project-tern-graceful-degradation]] — onboarding failures must
  degrade silently, not block the rest of the app
- [[project-tern-human-tests]] — the acceptance criterion (non-
  engineer pilot can pair from box in 10 minutes) IS a human test
