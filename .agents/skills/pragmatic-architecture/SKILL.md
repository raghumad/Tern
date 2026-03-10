---
name: Pragmatic Architecture
description: Principles and patterns for avoiding over-engineering and maintaining a lean, Maintainable codebase.
---

# Pragmatic Architecture Skill

This skill provides guidelines to prevent "Architectural Bloat" and ensure that the codebase remains simple, idiomatic, and focused on functional requirements rather than abstract patterns.

## Core Principles

### 1. The Rule of Three
Do not abstract until you have at least three distinct use cases. One-off classes for single methods or single-use sensors are an anti-pattern.

### 2. Threshold Centralization
All performance budgets, memory watermarks, and safety limits must be centralized in a single, well-documented source of truth (e.g., `MemoryPressureLevel.kt`). Never hardcode or duplicate thresholds in specialized managers.

### 3. Local Handling Over Generalized Recovery
Prefer simple `try-catch` and local fallback logic over generalized "Recovery Managers." If an operation fails, the calling code should decide how to degrade gracefully based on its specific context.

### 4. Class Consolidation
If a class is just a wrapper for a few platform APIs (e.g., `SimpleMemoryMonitor`), it should be absorbed into its primary consumer or a single unified utility.

### 5. Inline Small Utilities
Very small utility functions (like coordinate normalization) should be extension functions or inlined in the most relevant utility file (`MapOverlayCacheUtils.kt`) rather than having their own files.

## Application Checklist
- [ ] Are there fewer than 5 classes involved in this single feature?
- [ ] Are all constants centralized?
- [ ] Is the recovery logic local to the failure point?
- [ ] Can this logic be expressed as a simple extension function instead of a new class?
- [ ] Is this abstraction "future-proofing" for a future that hasn't arrived? (If yes, delete the abstraction).
