# Publishing checklist (Google Play)

Not started — no Play Console account yet. The current release build works
(signed + R8-shrunk; see commit 089f1d2 / `tern-release-build` memory), but it's
signed with a **throwaway dev upload keystore** generated during setup. Everything
below is for when we actually publish.

## Signing
- [ ] **Create a real upload key** (don't reuse the dev `upload-keystore.jks`).
      `keytool -genkeypair -alias <alias> -keyalg RSA -keysize 2048 -validity 9125 ...`
- [ ] Enrol in **Play App Signing** (Google holds the app signing key; we keep only
      the upload key — lets us recover if the upload key is lost). Strongly preferred.
- [ ] Put the new key in `tern-android/keystore.properties` (already gitignored).
      **Back it up** (password manager + offline copy).
- [ ] Confirm the dev keystore/password are never used for a published build.

## Build artifact
- [ ] Ship an **AAB**, not an APK: `./gradlew bundleRelease` (Play requires .aab).
      Sanity-check it with `bundletool build-apks --connected-device` before upload.
- [ ] Bump `versionCode` (currently 1) and `versionName` (currently "1.0") per release.
- [ ] Confirm final `applicationId` = `com.ternparagliding` (immutable after first publish).
- [ ] `targetSdk` meets Play's current minimum (now 36 — fine; re-check at submit time).

## Release build correctness (R8)
- [ ] Full functional pass on the **release (minified)** build, not just debug —
      especially every Jackson path: weather load, airspace/PG-spot/task/spatial
      caches, remembered-device restore, offline geocoder. (Obfuscation is off via
      `-dontobfuscate` precisely because these map DTOs by name; keep it off unless
      those DTOs are migrated to kotlinx.serialization.)
- [ ] Confirm demo/replay/broadcast surfaces are absent (already verified once; re-check).
- [ ] Consider a crash reporter (Crashlytics/Sentry) before public release.

## Store listing & compliance
- [ ] **Privacy policy URL** (required) — covers location, Bluetooth, any analytics.
- [ ] **Data safety form** — declare location (precise/background?), Bluetooth, network.
- [ ] Background-location justification if we ever request it (in-flight tracking) —
      Play scrutinises this heavily; confirm current permissions vs. what we declare.
- [ ] Content rating questionnaire.
- [ ] Listing assets: app icon, feature graphic, phone screenshots, short + full
      description, category (Sports / Maps & Navigation).
- [ ] Target audience / ads declaration (no ads).

## Pre-launch
- [ ] Upload to **internal testing** track first; run the pre-launch report (Play's
      automated device crawl) and fix crashes.
- [ ] Verify on a clean device (fresh install, real upload-key signature).
