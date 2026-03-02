# Code Cleanup Skill

This skill provides a systematic approach to maintaining code quality, ensuring the project remains clean, readable, and performant.

## 🎯 Objective
Remove redundancy, fix formatting, and align code with Tern's high-performance architectural standards.

## 🛠️ Instructions

### 1. Identify Redundancy
*   Search for duplicate Redux dispatches (e.g., double-calling `dispatchLocationReady`).
*   Look for legacy methods that have been superseded by the `OverlayCoordinator`.
*   Remove commented-out code blocks or obsolete TODO markers.

### 2. Performance Check
*   Ensure all heavy processing is moved to `Dispatchers.IO`.
*   Verify that map operations are properly debounced (300ms interactive, 2000ms flight).
*   Check for unnecessary UI recompositions caused by over-frequent state updates.

### 3. Formatting & Logging
*   Fix inconsistent line breaks.
*   Downgrade excessive `Log.e` to `Log.d` if the issue is expected/handled.
*   Ensure imports are grouped and optimized.

### 📜 Related Principles
*   **High-Frequency Batching**: Prevent state storms.
*   **Fail-Safe UI**: Ensure error handling is robust but quiet.
