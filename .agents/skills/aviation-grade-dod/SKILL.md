---
name: Aviation-Grade Definition of Done (DoD)
description: A mandatory checklist for ensuring technical excellence and pilot-centric validation before marking a feature as complete.
---

# 🏁 Aviation-Grade Definition of Done (DoD)

This checklist provides the definitive, data-driven criteria for marking a feature as "Done." It ensures technical reliability and pilot safety through objective verification.

## 🛠️ 1. Build & Technical Hygiene
- [ ] **Compiler Verification**: 
    - [ ] Execution of `./gradlew :app:compileDebugKotlin` returns `Exit code: 0`.
    - [ ] Execution of `./gradlew :app:compileDebugAndroidTestKotlin` returns `Exit code: 0`.
- [ ] **Static Analysis**: 
    - [ ] Zero NEW errors in `./gradlew lintDebug`.
    - [ ] No "Unstable" Compose detections in `compose-compiler` reports.
- [ ] **Test Coverage**: 
    - [ ] Feature logic coverage resides in `Middleware` or pure functions (Unit Tested).
    - [ ] Coverage metrics in `jacocoDetailedReport` show zero regressions for safety-critical packages (`model`, `redux`).

## 📐 2. Architectural Protocol
- [ ] **Pragmatic Scope**: Feature implementation involves fewer than 5 new classes (ref. [Pragmatic Architecture](file:///home/raghu/src/Tern/.agents/skills/pragmatic-architecture/SKILL.md)).
- [ ] **Source of Truth**: All new performance, memory, or safety thresholds are centralized (e.g., in `MemoryPressureLevel.kt`) rather than inlined.
- [ ] **Redux Strictness**: State changes are triggered ONLY via `MapAction` -> `Middleware` -> `Reducer`. Zero direct state mutations.

## 👁️ 3. Semantic UI & BDD Verification
- [ ] **Pilot Status Visibility**: Background operations (caching, sync) are represented by active UI state changes (Icons, HUD shields).
- [ ] **BDD Fidelity**: At least one pilot-centric `scenario` added to the instrumented test suite.
- [ ] **Assertion Accuracy**: UI tests assert against unique `Modifier.testTag` IDs rather than text strings (to ensure localization stability).
- [ ] **Empirical Proof**: Verified the existence of BDD screenshots in `build/reports/bdd-report`.

## 🕹️ 4. Professional UX Metrics
- [ ] **Touch Target Precision**: Minimum `48dp x 48dp` (preferably `64dp`) touch targets for all interactive elements (ref. [Aviation UX Standards](file:///home/raghu/src/Tern/.agents/skills/aviation-grade-ux/SKILL.md)).
- [ ] **Haptic Loop**: All state-mutating actions (Save, Deletion, Waypoint Update) trigger a `VibrationEffect` or `HapticFeedback`.
- [ ] **Contrast Compliance**: Critical flight data (Altitude, Distance) uses high-contrast tokens (e.g., `AeroSlate.PrimaryContrast`).
- [ ] **The 0.5s Rule**: Critical screen state transitions are glanceable and understood within 0.5 seconds in high-stress simulation.

## 🛡️ 5. Resilience & Stability Protocol
- [ ] **Root Cause Analysis (RCA)**: 
    - [ ] Any regressions or high-risk bugs (Lifecycle, Concurrency, Source of Truth) discovered must have a [5 Whys analysis](file:///home/raghu/src/Tern/.agents/skills/aviation-grade-rca/SKILL.md) performed.
    - [ ] The RCA must result in a structural "Architectural Guardrail" that prevents the entire class of failure.
- [ ] **State Isolation**: 
    - [ ] Verified that state is correctly cleared between tests in `MapVisualTest.tearDown()` or equivalent.
    - [ ] No "Ghost" instances (managers, coroutines, or cache managers) remain after a session is cleared.
- [ ] **Idempotent Guards**: 
    - [ ] All critical initialization and state-setting actions have idempotent logic to prevent redundant background noise.
