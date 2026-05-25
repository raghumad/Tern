# Display, Audio, Input, and Connectors

Back to [index](custom-mezulla-design-reference.md).

---

## Display: 1.54" e-ink (setup + fallback, not in-flight UI)

- **Primary:** 1.54" 2-color e-ink. Extended-temperature variant
  (Pervasive Displays preferred for cold-weather refresh
  reliability).
- **Alternative supplier:** Waveshare 1.54" if cost dominates
  (cheaper, no extended-temp option — accept slower refresh in
  alpine cold).
- **Role:** setup/pairing QR display + standalone-mode emergency
  display + persistent "I am alive" status. **Not** the in-flight UI
  — speaker + phone handle that.

**Why this size / why e-ink:**
- Per the two-phase model + modality split, display is
  consulted rarely during flight. Smaller is fine.
- E-ink reads perfectly in sunlight (better than paper). Critical
  for the pairing QR at launch.
- Zero power between refreshes — display can persist for days,
  showing battery + status, with no battery impact.
- QR codes render beautifully (high contrast, crisp).

**Rejected for the in-flight role:**
- **Standard OLED** (LilyGo SSD1306): unreadable in direct sun.
- **Standard TFT LCD:** unreadable in direct sun without expensive
  transflective treatment.
- **3-color e-ink:** refresh ~15 s — too slow.

**Considered if vario-on-device becomes a real product goal:**
**Sharp Memory LCD** (LS027B7DH01 or similar). Sunlight-readable
like e-ink but with fast refresh (60 Hz capable) — suitable for
continuous vario display on the device itself. More expensive
($15-25). Move to this only if real flight data shows pilots want
vario readout on Mezulla rather than on phone.

---

## Audio: speaker + I2S amp (primary in-flight communication)

- **Audio amp:** **MAX98357A** (I2S DAC + class-D, mono). Common in
  IoT, well-supported, ~$5.
- **Speaker driver:** small enclosed 4-8 ohm driver, 1-3 W, 20-32 mm.
  PUI Audio AS-2520 or Knowles SR series for premium.
- **Total BOM:** ~$7-10. Trivial.

**Role:**
- **Pre-pairing:** boot confirmation, pairing progress tones,
  pairing complete
- **In-flight events:** buddy joined / went stale / SOS, low battery
- **Vario audio output** (if vario fusion is enabled) — continuous
  variable tones during flight, matching pre-existing pilot
  interaction patterns
- **Safety-critical alert path** independent of phone — works when
  phone is muted / in pocket / dead

**Design dependency:** audio language. What does "buddy joined" vs
"SOS" vs "vario climb" sound like? Needs to be designed and tested
in real flight conditions. Prototypable now on the SunFounder
kit (which has both amp and speaker).

**Audio language principles:**
- SOS is unique, loud, and unmistakable — never confused with
  anything else
- Vario behavior matches what pilots already know (rising pitch
  with climb rate)
- Distinguishable in noisy cockpit (wind, other audio sources)
- Brief, glanceable distinctions (no need to think "was that
  buddy-joined or stale?")

---

## Input: crown (rotary encoder + push) + single backup button

- **Primary input:** **Bourns PEC11R sealed rotary encoder + push**
  (~$5-8). Side-mounted, knurled metal knob, ~5 mm protrusion.
- **Backup input:** single tactile button elsewhere (combinable
  with power / USB area). Ensures crown isn't a single point of
  failure.
- **Premium / v2 upgrade path:** magnetic encoder (AMS AS5600) with
  custom bearing assembly — contactless sensing, much higher
  durability. Document in BOM.
- **Recessed mount or guard ring** to prevent harness-snag.

**Interaction model:**
- Rotate = scroll / adjust value
- Click = select / confirm
- Long-press (1-2 s) = back / menu / close
- **Rotate-to-arm + click-to-confirm** = dangerous actions (SOS,
  pairing reset). Deliberate, glove-friendly, hard to trigger
  accidentally.
- Hold during boot = factory reset / pairing mode

**Why a crown over multiple buttons:**
- **Glove-friendly.** Turning a knurled wheel through thick winter
  gloves works; pressing small tactile buttons through them is hit-
  or-miss.
- **Eyes-free operation.** Detents are felt, not seen.
- **Single mechanism = many functions** (scroll + click + long-press
  + combos). Fewer holes in the enclosure to seal.
- **Ideal SOS confirmation pattern** (rotate-to-arm + click). Buttons
  can't match this safety property.

**Honest concerns:**
- Mechanical wear (push switch dies first, ~50k clicks typical).
  Sealed Bourns or magnetic variants mitigate.
- Enclosure sealing — shaft = ingress point, needs O-ring.
- Snag risk on harness webbing — mitigated by recessed mount.

---

## Connectors and ports

- **USB-C** for charging only. PD support not required since
  Mezulla isn't negotiating high-power roles.
- **SMA female** for LoRa antenna (see [radio-and-antenna.md](radio-and-antenna.md)).
- **18650 cell holder** — snap-in or sled, pilot-accessible
  without tools.
- **GPS active patch antenna** — top-mounted, not on a connector
  (integrated into enclosure design).
- **No header / pin breakouts on the production board.** Dev/debug
  variants can populate them; production should be clean.
