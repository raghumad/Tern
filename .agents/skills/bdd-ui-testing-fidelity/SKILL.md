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

## 🎬 5. Closed-Loop State Verification
**Symptom:** Tests pass because the "Action" (e.g. `swipeMap`) was invoked, but the map never actually reached the destination or the overlays never rendered.
**Principle:** **Aviation-grade tests are Closed-Loop.** Every interaction must conclude with a state-based assertion (e.g. `assertMapLocation(lat, lon)`). Do not assume the Viewport followed the Redux command; verify the physical `MapView.mapCenter` directly.

## 🕹️ 6. Deterministic Interaction Steps
**Symptom:** Rapid swipes trigger the map's physics-based momentum (fling), causing non-deterministic "landing" zones in tests.
**Principle:** Simulated gestures must be deterministic. Use a higher number of `steps` (e.g. 20+) for swipes to provide sufficient friction, preventing the OS from injecting erratic momentum that throws the map off-course during automated execution.

## 🛡️ 7. Resilient Watermark Assertions
**Symptom:** UI tests break frequently because real-time weather or distance values shift by small amounts.
**Principle:** When testing against real-time data, utilize "Watermark" matching. Assert the existence of units (e.g., "kt", "km", "ft") and the general format (RegEx) instead of strict text equality. This ensures the communication channel from API-to-UI is healthy without being tethered to a static, mocked world.
