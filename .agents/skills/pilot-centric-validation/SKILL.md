---
name: Pilot-Centric Validation Standards
description: Principles for ensuring that every technical feature is validated through visible, narrative-driven pilot scenarios.
---

# 🛸 Pilot-Centric Validation Standards

In Tern, we do not test code; we validate **Pilot Outcomes**. A feature is only considered "Complete" when it survives a high-fidelity instrumented test that a pilot could read and understand.

## 👁️ 1. The Visibility Mandate
**The Principle:** If a feature does not manifest in the UI, it is invisible to the pilot. If it is invisible, it hasn't been validated.
**The Law:**
- Every background process (Sync, Caching, Analytics) must have a corresponding UI indicator (Icon, Banner, Gauge).
- Instrumented tests MUST assert on these UI elements (`testTag`) rather than internal Redux state.
- **Verification:** Check the BDD screenshot. If you can't "see" the feature working in the `.png`, the test is insufficient.

## 📖 2. The Narrative Anchor
**The Principle:** A test is not a function; it is a **Story**.
**The Law:**
- Use `scenario("Story Name") { ... }` blocks to frame the test as a pilot's journey (e.g., "The Mountain Record Attempt").
- BDD steps (`given`, `when`, `then`) must use pilot-centric language ("When I search for a launch," "Then I see a Class B warning").
- Every feature PR must include at least one new Pilot Story.

## 🔗 3. Redux-to-UI Integrity
**The Principle:** Middleware side-effects must be tracked through the entire Redux loop to the UI.
**The Law:**
- Never mock the `Middleware` itself. Test the actual `Middleware` by dispatching the triggering `Action` and asserting on the resulting `State` change as shown in the UI.
- Use `composeTestRule.runOnUiThread` to dispatch actions, then `composeTestRule.waitForIdle()` before making assertions.

## 🕹️ 4. Active Physical Mocking
**The Principle:** BDD steps are not stubs. They are actions.
**The Law:**
- A `when` step must perform a physical interaction (Long Press, Drag, Click) or a data-mutating Redux dispatch.
- Avoid "Silent Logging". A `ReportGenerator.logStep` without a corresponding UI change is a failure of fidelity.
- **Golden Example:** [AviationRoutePlanningTest.kt](file:///home/raghu/src/Tern/tern-android/app/src/instrumentedTests/kotlin/com/madanala/tern/test/AviationRoutePlanningTest.kt)

## 📸 5. Visual Evidence
**The Principle:** Screenshots are the single source of truth for UX validation.
**The Law:**
- Capture screenshots at every `given` and `then` milestone.
- Analyze screenshots specifically for **Aviation-Grade UX** adherence (Contrast, Glanceability, Hitboxes).

## 🌍 6. The Real-Time Mandate
**The Principle:** Safety-critical features must be validated against real-world chaos, not static mocks.
**The Law:**
- Instrumented tests MUST target real-time APIs (e.g., Open-Meteo, PGSpots) wherever possible.
- Avoid `injectMock*` helpers for paragliding spots or weather forecasts; use real IDs and coordinates.
- **Verification:** Ensure the test triggers a network fetch and waits for the real response.

## 📏 7. Watermark Validation
**The Principle:** Real-time data is dynamic; validation must be resilient but firm.
**The Law:**
- Use "Watermark" assertions (RegEx, Unit existence) instead of exact string matching.
- **BAD:** `assertTextContains("12.5 kt")` (Will fail if the wind changes).
- **GOOD:** `assertTextContains("kt")` or `assert(hasText(Regex("\\d+(\\.\\d+)? kt")))`.
- Ensure the unit (kt, km, ft) exists to verify the data layer is successfully mapped to the UI.
