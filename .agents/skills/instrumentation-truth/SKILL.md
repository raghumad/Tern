---
name: Instrumentation Truth
description: Principles for ensuring that instrumented tests reflect real-world data and logic, avoiding the "mock leakage" that leads to misleading test results and architectural debt.
---

# Instrumentation Truth

Instrumentation tests are the final line of defense before a pilot takes the app into the cockpit. Unlike unit tests, which isolate logic using mocks, instrumented tests must prove that the **entire system** works together truthfully.

## 核心原则 (Core Principles)

### 1. Mocks are for Units, Truth is for Instrumentation
-   **Unit Tests**: Use mocks/stubs to verify isolated logic (e.g., `Route.calculateDistance`).
-   **Instrumented Tests**: Must use the real Redux state, real middlewares, and real services (or high-fidelity proxies). 
-   **The Leakage Trap**: Hardcoding placeholders in UI components to "pass" a visual test is a violation of this principle.

### 2. High-Fidelity Data Proxies
-   Instead of returning static "Success" strings in middleware, use high-fidelity services that perform real calculations (e.g., spatial intersection for airspaces).
-   If a network call is mocked (via `MockWebServer`), the data returned must be geographically and temporally consistent with the test scenario (e.g., Mexico weather for a Mexico task).

### 3. Verifiable Narrative
-   Every `GIVEN` step in a BDD test should be inherently verified.
-   If the test says "Given the pilot is at launch", the map must be centered at the launch coordinates AND the HUD must show metrics consistent with being at launch (e.g., Distance to SS, ETA @ Goal based on current time).

## 🚩 Anti-Patterns (Mock Leakage)

-   **Placeholder UI Flags**: Hardcoding `val hasCollision = true` in a Composable.
-   **Logging Stubs**: Logging "Airspace clear" in middleware without performing a check.
-   **Static ETAs**: Showing "13:45" in the HUD regardless of the current system time or route length.
-   **Coordinate Mismatch**: Loading Boulder airspaces while the pilot is in Mexico.

## 🛠️ Verification Checklist

- [ ] Does the UI read metrics from the Redux state or is it hardcoded?
- [ ] Does the middleware dispatch real results or just stubs?
- [ ] Is the mock server data (if used) geographically relevant to the test?
- [ ] If I change the test coordinates, do the HUD metrics update accordingly?
