# Play Store submission content — Tern (Epic 04 Story 4.3)

Draft content to paste into Play Console at submission. **Legal note:** the data-safety
section is drafted honestly from the app's real behaviour + `ternparagliding-web/site/privacy.html`,
but the privacy policy itself says to review with legal before submission — do that, especially
the location/mesh gray areas flagged below.

App: **Tern** · package `com.ternparagliding` (immutable after first publish) · Android · free · no ads.

---

## 1. Store listing

**App name (≤30 chars):** `Tern — offline buddy tracking`  *(30)*

**Short description (≤80 chars):**
`See your crew with zero signal. Offline maps, airspace, vario & flight logbook.`  *(79)*

**Full description (≤4000 chars):**
```
Tern is a paragliding flight computer that keeps working when the signal doesn't — and lets you see your crew on the map with no cell service at all.

TRULY OFFLINE — NOT JUST THE MAPS
Pan anywhere and maps, terrain, airspace, launch sites and thermal hotspots stream in on their own, then stay cached for when you're out of range. No "download region" button to remember.

SEE YOUR CREW WITH ZERO SIGNAL
Pair a small low-cost LoRa radio and your buddies appear live on your map over a private, encrypted mesh — no cell tower, no internet, no monthly fee. A private team, so you see only your crew, not every stranger in the sky. The mesh relays, so range grows with your group.

A REAL FLIGHT COMPUTER
• Live vario with climb/sink-coloured track and thermal cores
• Next-waypoint guidance, glide ratio, wind estimate
• A glanceable, one-handed, eyes-out flight deck
• Connect an XC Tracer Bluetooth vario, or fall back to phone GPS automatically

PLAN COMP-STYLE TASKS ON THE MAP
Drop turnpoints straight onto the terrain — Tern draws the route and updates legs, total distance and a time estimate as you go. Set the start, turns and goal with cylinder radii, the way comps actually score. Plan at home, fly it offline in the field.

PER-SITE WEATHER
Tap a launch for a pilot's forecast: wind on the hill, thermal strength hour by hour, cloudbase and a plain go / caution call. Fetched on signal, banked for offline.

FLIGHT LOGBOOK
Your flight is recorded automatically and saved to a local logbook with at-a-glance stats. Export a standard IGC file, or — if you choose to link an optional Spedmo account — upload it automatically.

PRIVACY-FIRST
Location is used only while the app is open — there is NO background-location permission. Your tracks stay on your device unless you opt in to sharing them. No ads, no selling your data.

Tern works fully with no account. An optional Spedmo account only adds online extras (flight upload, club-as-team) and is entirely opt-in.

Android first. iOS later.
```

**Category:** Maps & Navigation (alt: Sports). **Tags:** paragliding, navigation, offline maps.
**Contact email:** info@ternparagliding.com
**Privacy policy URL:** https://ternparagliding.com/privacy
**Website:** https://ternparagliding.com

**Graphic assets needed (you/me to produce):**
- App icon 512×512 (have `app-icon-round` source).
- Feature graphic 1024×500 (can build from the site banner / tern-mark).
- Phone screenshots ≥2 (min 320px, 16:9-ish) — reuse the site media frames: map hydration,
  flight deck, per-site weather, **the new task-planning capture**. (We already have these as video;
  pull stills, or reuse `shot-*.png`.)

---

## 2. Data safety form

Google asks, per data type: **Collected?** (leaves device to you/third party), **Shared?** (to third party),
**Optional or required?**, **Purpose**, **Encrypted in transit?**, **User can request deletion?**

Tern has **no Tern-owned servers** — nothing is collected to a Tern backend. Data leaves the device only
(a) device-to-device over the local LoRa radio mesh, and (b) to **Spedmo** if the user explicitly opts in.

| Data type | Collected (to Tern)? | Shared (3rd party)? | Optional? | Purpose | Notes |
|---|---|---|---|---|---|
| **Precise location** | No (no Tern server) | **Yes — to Spedmo, only if opted in**; also broadcast to your own team over local radio | **Optional** | App functionality (live buddy position, flight upload) | While-in-use only; **no background location**. ⚠ see gray-area note |
| **Other app activity (flight tracks / IGC)** | No | **Yes — to Spedmo, only if opted in** | Optional | App functionality (logbook upload) | Off by default |
| **Device/other IDs, contacts, messages, financial, health, photos** | No | No | — | — | none collected |

**Other declarations:**
- Data **encrypted in transit:** Yes — Spedmo upload over HTTPS; mesh traffic is encrypted (team PSK).
- **Account required:** No (app works fully without one).
- **Data deletion:** Yes — on-device data removed by uninstall; Spedmo-uploaded data managed/deleted in
  the user's Spedmo account (link this in the form).
- **Ads:** None. **Data sold:** No.
- **Families policy / target children:** No — not directed at children.

⚠ **Gray areas to confirm before submit:**
1. **LoRa mesh = "sharing"?** Play's data-safety is oriented to internet/third-party transmission;
   the mesh is peer-to-peer device-to-device over radio (no Tern/3rd-party server). Decide with the
   policy text whether to declare it as "shared." Conservative/honest choice: mention it, since position
   does leave the device. The Spedmo opt-in path is unambiguously "shared with a third party."
2. **Bluetooth scanning** is declared (in the manifest) as **NOT** used to derive location — keep that
   consistent in the form's permissions rationale.
3. Confirm the final list of runtime permissions in the **release** manifest matches these answers.

---

## 3. Content rating questionnaire (IARC)

A navigation/sports utility with no objectionable content. Expected answers → rating **Everyone / PEGI 3**:
- Violence, sexual content, profanity, controlled substances, gambling: **No** to all.
- User-generated content / user interaction: the buddy mesh shares position with a private team — declare
  **user-to-user location sharing** if asked (it's private/team-scoped, not public broadcast).
- Shares user's physical location with other users: **Yes** (private team) — answer truthfully.

---

## 4. App content / other Play declarations
- **Target audience & content:** 18+ pilots (or 13+); not designed for children.
- **News app:** No. **COVID/health:** No. **Government app:** No.
- **Ads:** No.
- **Permissions needing justification:** location (while-in-use; foreground navigation/recording),
  Bluetooth (vario + radio board; scan flagged not-for-location), foreground service (flight recording
  while screen-off — declare the foreground-service type). **No background location** → no background-
  location declaration / video needed (a meaningful approval simplifier).
- **Government/financial features:** none.

---

## 5. First upload mechanics (ties to Publishing checklist)
- Build **AAB** (`./gradlew bundleRelease`), real upload key, enrol in **Play App Signing**, back up the key.
- Start on the **internal testing** track (≤100 testers, no gate) to shake out the pre-launch report.
- Then **closed testing** for the 12-testers × 14-days production gate (recruit via Spedmo — see
  [[tern-play-launch-gtm]] / the GTM plan).
- `versionCode` / `versionName` bump each upload; confirm `targetSdk` ≥ Play minimum.
