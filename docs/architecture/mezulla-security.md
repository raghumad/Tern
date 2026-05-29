# Mezulla security model — read before judging the unencrypted BLE link

## TL;DR

Mezulla **intentionally does not use BLE link-layer encryption**.
Authentication is the QR token at the application layer, not BLE
bonding. Position and SOS broadcasts on the BLE link travel in
plaintext. The same data is already on LoRa, so this is not new
exposure. Tern surfaces the same disclosure to the pilot on the
pair-priming screen.

If your threat model requires BLE encryption, **you are using the
wrong radio** — see "When this is the wrong choice" at the bottom.

## What we do for authentication

| Mechanism | Where it lives |
|---|---|
| Per-boot random QR token (4 random bytes → 8 hex) | OLED QR + `tern://p?…&t=<token>` deep link |
| Claim packet must carry the token byte-for-byte | `Claim` cmd on `PRIVATE_APP` port 256, verified in firmware |
| owner_id stored on board at claim time | every subsequent privileged command is gated on owner_id match |

The QR token authenticates **the act of claiming the board**. The
owner_id authenticates **every subsequent command**. There is no
shared secret between phone and board after pairing other than the
owner_id, and owner_id is held in board flash, not transmitted on
every command.

## What we explicitly give up

- **Link confidentiality.** Position/SOS broadcasts on BLE travel
  in plaintext. A BLE sniffer within ~10 m can read them.
- **Pair-time confidentiality of the token.** During the ~5 s window
  between QR scan and claim, the token traverses the unencrypted
  GATT link as well as being visible on the OLED.

## Why we picked this trade-off

1. **Spec-mandated dead end.** Per BLE Core Spec Vol 3 Part H
   Table 2.8 + AOSP source, non-system Android apps cannot silently
   complete BLE bonding. `BluetoothDevice.setPairingConfirmation()`
   requires `BLUETOOTH_PRIVILEGED` (system-only). And for
   app-initiated bonds the IO Capability negotiation defaults to
   `NoInputNoOutput`, forcing Just Works (variant 3) regardless of
   what the peripheral advertises. So even peripherals that try to
   negotiate Passkey Entry end up in Just Works — which only a
   privileged app can confirm without user interaction.
2. **Even encrypted Just Works isn't much.** Just Works pairing has
   no MITM protection; a passive attacker present during pairing can
   recover the session key. So "encryption via Just Works" against a
   pair-time attacker is mostly theatre.
3. **The same data is already on LoRa.** Meshtastic's default
   channel encryption is a shared key, not per-pair — any nearby
   Meshtastic radio on the same channel can already read position
   broadcasts. BLE plaintext exposes nothing the LoRa side doesn't
   already.
4. **Concrete UX win.** No system "Pair and connect" dialog ever
   appears. The pilot scans the QR → Tern shows the pair-priming
   screen → the persistent link comes up silently. First-pair is
   one camera tap; subsequent connects are zero taps.

## What attackers can and can't do

| Attack | Effect |
|---|---|
| Passive BLE sniffer within ~10 m | reads positions, callsign, telemetry |
| Active attacker tries to spoof a peer at GATT layer | **blocked** at firmware — owner_id mismatch |
| Attacker connects to the board without the QR token | **blocked** at firmware — token mismatch on claim |
| Attacker recovers the QR token in the 5-s pair window | could claim a board they don't have eyes on; mitigation: pair indoors / RF-quiet |
| Attacker injects bogus position packets into the mesh | irrelevant to BLE — same exposure on LoRa side |

## Firmware-side implementation

- `MezullaOwnershipModule` constructor sets
  `config.bluetooth.mode = NO_PIN` and `wait_bluetooth_secs = 0`,
  for BOTH unclaimed AND claimed states.
- `NimbleBluetooth::setupService` (stock Meshtastic) sees NO_PIN
  and creates characteristics WITHOUT `_ENC`/`_AUTHEN` flags. So
  Android never has to bond to read/write them, and never raises
  `ACTION_PAIRING_REQUEST`.
- `handleClaim` does NOT switch the BLE mode after claim — staying
  NO_PIN forever avoids the bond-loop that mode switching used to
  cause.

## Phone-side implementation

- `BlePairingService` connects unencrypted GATT, sends the claim
  packet, disconnects. No bond is requested or expected.
- `MezullaConnectionManager` holds a persistent unencrypted GATT
  connection. No `PairingReceiver` exists — there is no broadcast
  to intercept.
- `PairingPrimingScreen` (in `mezulla/ui/`) surfaces the
  authentication-and-no-encryption disclosure on every pair so the
  pilot is reminded each time.

## When this is the wrong choice

If you are:
- shipping a payments / authentication peripheral,
- storing PII or medical records on the board,
- sending data the user would clearly object to having visible to
  any nearby BLE sniffer,

then drop this architecture and use **Bluetooth LE Secure
Connections + MITM with Passkey Entry** — accepting the UX cost of
the pilot typing a 6-digit code at first pair. Mezulla is not in
any of those categories.
