# Current focus: Epic 01 — buddy-flying with real hardware

Working toward: [Epic 01](epic-01-peer-awareness-and-sos.md)

## Where we are (2026-05-27)

QR pairing works end-to-end on real hardware. A pilot scans the
board's QR with a phone camera, the app claims ownership over BLE,
the board persists it, and after reboot the board remembers.

The convergence test (swarm simulator → peer markers on map) passes
in the emulator. But the simulator bypasses the wire protocol — it
pushes PeerState directly into Redux without ever encoding/decoding
Meshtastic protobufs. The real BLE path (ToRadio/FromRadio) has no
wiring yet.

No position data flows between pilots. The pairing connects and
disconnects. There is no persistent BLE session, no position
broadcast, no position receive.

## What's done

| Area | Status | Evidence |
|------|--------|----------|
| Swarm simulator (WS1) | Done | IGC → playback → propagation → PeerState |
| MeshtasticConnection abstraction (WS2) | Done (simulator only) | Bypasses wire protocol |
| Peer markers on map (WS3.1–3.2) | Done (emulator) | Convergence BDD passes |
| QR pairing firmware (WS-F2) | Done | Claim/query/release on PRIVATE_APP port 256 |
| QR pairing app | Done | Deep link → BLE scan → connect → claim → persist |
| BLE mode transitions | Done | NO_PIN unclaimed → FIXED_PIN claimed → NO_PIN released |
| OLED screen dump | Done | SSD1306 buffer decoded to image, QR verified programmatically |
| On-demand screendump | Done | Query command triggers dump (like QEMU screendump) |
| Test bench | Done | Ulefone (WiFi adb) + board (USB serial), automated |

## The gap

```
Simulator:  IGC → PeerState → Redux → map              (works)
Real:       GPS → ToRadio → BLE → board → LoRa → air →
            board → FromRadio → BLE → PeerState → Redux → map
                   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                   nothing exists for this part
```

The simulator proves the rendering works. The real path proves the
radio works. They need to meet in the middle at the Meshtastic
protobuf layer (ToRadio/FromRadio).

## What's next

### 1. Persistent BLE connection

After pairing, keep the GATT connection open. Today the claim flow
connects, sends the claim packet, and disconnects. The connection
needs to stay alive for ongoing position exchange.

- Subscribe to FromNum GATT notifications (board signals new data)
- Read FromRadio characteristic to receive incoming mesh packets
- Write ToRadio characteristic to send outgoing packets
- Handle disconnect/reconnect gracefully (board power cycle, BLE
  range loss, phone sleep)
- `BleConnection : MeshtasticConnection` — plugs into the same
  interface the simulator uses, so Redux/UI code doesn't change

### 2. ToRadio/FromRadio codec

Encode and decode Meshtastic protobufs in Kotlin. No custom packet
format — Meshtastic already defines everything we need:

- `POSITION_APP` (portnum 3): lat, lon, altitude, time
- `NODEINFO_APP` (portnum 4): device name, hardware model
- `TEXT_MESSAGE_APP` (portnum 1): future, not needed yet
- `ADMIN_APP` (portnum 6): config read/write (for auto-region)

Unit-testable in pure Kotlin, no BLE or emulator needed. This is
where protobuf field number bugs get caught (we already hit one:
`want_response` was field 5, should have been field 3).

### 3. Position exchange

With persistent BLE and the codec working:

**Send:** Phone GPS → encode as Meshtastic Position protobuf →
wrap in MeshPacket → write to ToRadio → board broadcasts over LoRa.
Cadence TBD (balance freshness vs board battery on 2–5 hour flight).

**Receive:** Board receives LoRa packet from peer → queues in
FromRadio → phone reads via BLE → decode Position → PeerState →
Redux → peer marker on map.

This is the moment two pilots actually see each other.

### 4. Auto-region from GPS

The phone knows its GPS location. On BLE connect, push the correct
LoRa regulatory region to the board:

- GPS coordinates → ITU region mapping (US, EU_868, IN, etc.)
- Read board's current `config.lora.region` via admin channel
- If mismatched, push the correct region (radio restarts briefly)
- Only push when region actually changed, not on every connect

The pilot never thinks about radio regulations. Fly in France, it's
EU_868. Come home, it's US. Zero ceremony.

### 5. Test build flag (MEZULLA_TEST_BUILD)

A build-time flag that forces `lora.region = UNSET`, making the
radio physically unable to transmit. Test firmware cannot spam the
mesh.

```ini
# platformio.ini
[env:tlora-v2-1-1_6-test]
extends = env:tlora-v2-1-1_6
build_flags = ${env:tlora-v2-1-1_6.build_flags} -DMEZULLA_TEST_BUILD
```

```cpp
// MezullaOwnershipModule constructor
#ifdef MEZULLA_TEST_BUILD
config.lora.region = meshtastic_Config_LoRaConfig_RegionCode_UNSET;
#endif
```

The test bench uses `-test` environment. Production uses the normal
environment where the phone sets region from GPS. Structural safety,
not a runtime toggle.

### 6. SOS alert UI (Story 1.4)

Safety-critical. One-button SOS that broadcasts position over LoRa.
Receiving phones show a high-priority alert with the sender's last
known position.

- Protocol decision: Meshtastic alert channel or PRIVATE_APP
  sub-command? Alert channel is upstream-compatible but may lack
  retry semantics.
- Glove-friendly UI with confirmation step (prevent accidental SOS)
- OLED confirmation on the board (Story 1.6)

### 7. Overlay reactive rendering (S1)

Safety-critical. Overlays accumulate as the pilot flies. An 11-hour
XC will OOM and crash the app. Fix: re-render from Hilbert cache on
viewport change, discard out-of-view overlays. See
[overlay-infrastructure-audit.md](overlay-infrastructure-audit.md).

## Priority order

| Priority | Item | Why |
|----------|------|-----|
| **1** | Persistent BLE connection | Gates everything — no data flows without it |
| **2** | ToRadio/FromRadio codec + unit tests | Catches wire bugs early, no hardware needed |
| **3** | Position exchange (send + receive) | The feature: pilots see each other |
| **4** | Test build flag (MEZULLA_TEST_BUILD) | Safety: test firmware can't transmit |
| **5** | SOS alert UI | Safety-critical for pilots |
| **6** | Overlay reactive rendering (S1) | Prevents OOM crash on long flights |
| **7** | Auto-region from GPS | Zero-ceremony regulatory compliance |
| **8** | OLED status screens (Stories 1.5–1.6) | Board is useless without status display |
| **9** | Peer markers human test (Story 1.3) | Emulator-verified only, needs real hardware |
| **10** | Auto-reconnect lifecycle | BLE drops mid-flight robustness |
| **11** | Graceful degradation audit (Story 1.7) | Verify all failure paths are silent |

Items 1–4 are tightly coupled — they form one deliverable: "two
pilots see each other." Items 5–6 are safety-critical but
independent. Items 7–11 complete the Epic 01 MVP.

## Later (after Epic 01)

- **Epic 02: Traffic awareness** — FANET/FLARM/ADS-L
- **Known test failures** — route planning, weather UX (parked)
- **Architecture cleanup** — overlay code quality, upstream PR prep
- **Upstream Meshtastic PR** — generalize Mezulla → generic pairing

## Test infrastructure

| Layer | What it proves | How |
|-------|---------------|-----|
| Codec unit tests | Protobuf encode/decode correct | Pure Kotlin, no hardware |
| Emulator BDD | Rendering + Redux pipeline correct | Swarm simulator, screenshots |
| Hardware instrumented test | BLE + GATT + Meshtastic works | Real phone + real board (radio off) |
| OLED screen dump | Board display is correct | Serial hex → PIL → pyzbar |
| Human test | Pilot can actually use it | Real hardware, real conditions |

A feature is never done until its human test passes.
