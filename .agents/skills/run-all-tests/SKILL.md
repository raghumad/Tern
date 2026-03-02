# Run All Tests Skill

This skill ensures the stability of the Tern project by running the full verification suite.

## 🎯 Objective
Verify that no regressions have been introduced and that the application maintains its "Safety-First" standards.

## 🛠️ Instructions

### 1. Pre-requisites
*   Ensure an Android emulator (e.g., Pixel 9 Pro API 35) is running.
*   Ensure the device has enough disk space for test artifacts.

### 2. Execution
Run the full instrumentation suite:
```bash
./gradlew testAll
```

### 3. Verification criteria
*   **100% Pass Rate**: Every single test (41 currently) must pass.
*   **Time**: Expect roughly 15-20 minutes for execution.
*   **Logs**: Check if any "Safety Failure" or "Visual Discontinuity" warnings appeared.

### 📜 Related Principles
*   **Always Verify**: Never submit a task without a full green run.
*   **BDD Stability**: Tests include user stories and safety fallback scenarios.
