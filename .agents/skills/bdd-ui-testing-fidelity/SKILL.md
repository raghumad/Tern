---
name: BDD UI Testing Fidelity
description: Principles for creating realistic, visually accurate, and descriptive Instrumentation Tests using the BDD framework.
---

# 📸 BDD UI Testing Fidelity

When architecting UI automated tests utilizing the custom BDD reporting framework, the code serves a dual purpose: validating application logic and generating human-readable architectural documentation via execution screenshots and narrative logs.

## 📍 1. Geometric Viewport Alignment
**Symptom:** Tests pass perfectly on Redux states, but the generated `.png` screenshots show an empty map.
**Root Cause:** The `MapVisualTest` orchestration heavily anchors the underlying OSMDroid camera view to specific default coordinates (e.g., Boulder, CO `40.015, -105.27`). 
**Principle:** Whenever injecting mock `Route`, `PGSpot`, `Airspace`, or `Waypoint` objects into the Redux `MapStore`, **you must align their simulated Lat/Lon coordinates to naturally intersect with the default Map viewport.** Do not place mock objects at `(0.0, 0.0)` or distant countries unless the test explicitly commands the camera to pan there.

## 🗣️ 2. Avoiding "Peacock" Terminology
**Symptom:** Test function names like `scenario1_configureFAITaskParameters` or `testComplexWorkflow` are opaque. BDD step strings like `due to high overlay density` are scientifically ambiguous.
**Principle:** Use explicit, standard JUnit test naming conventions that directly express the domain capability being tested. Inside BDD String definitions, utilize explicit architectural constraints rather than relative adjectives.
*   **BAD:** `testComplexWorkflow`, `scenario1_testLaunch`, `"due to high overlay density"`
*   **GOOD:** `testCrossCountryRoutePlanningLifecycle`, `"adaptive budget capacity system should throttle visible geometry limits"`

## 🎭 3. Active Physical Mocking vs. Silent Logging
**Symptom:** A BDD step reads `when("I create a physical map route")`, but the code block only performs `ReportGenerator.logStep(...)` without modifying any UI state.
**Principle:** BDD steps are not stubs. Every `when` or `given` action MUST trigger physical Redux `MapAction` dispatches or Espresso UI interactions that manifest visually on the Compose canvas. The subsequent `then` step must natively assert those elements exist *before* the BDD engine captures the screenshot.

## ⏱️ 4. Logcat Temporal Synchronization
**Symptom:** A BDD HTML report indicates `RESULT: Scenario Passed`, but the developer cannot identify *when* that step physically occurred inside the massive Android Logcat execution stream.
**Principle:** BDD Step models must natively capture `System.currentTimeMillis()`. The HTML `ReportGenerator` MUST print the formatted timestamp `[HH:mm:ss.SSS]` inside every execution step block. This guarantees 1:1 cross-referencing between the UI assertion milestone and the underlying system `Log.i` performance metrics.
