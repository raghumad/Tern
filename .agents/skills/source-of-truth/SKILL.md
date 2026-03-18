---
name: Source of Truth
description: Diagnostic patterns for identifying the single source of truth in class and framework design.
---

# Source of Truth Skill

The goal of this skill is to eliminate architectural redundancy and conflicting state by ensuring every piece of data, configuration, or logic has exactly **one** owner and **one** primary representation.

## Diagnostic Questions

Before implementing a new class, state property, or constant, ask the following questions:

### 1. Where is the absolute "Origin"?
- Does this data exist elsewhere in the system?
- If yes, why am I creating a new copy? 
- **Rule**: Never duplicate. Always reference the owner.

### 2. Is this "Primary State" or "Derived State"?
- Can this value be calculated from other existing data?
- **Rule**: If it can be calculated, it should not be stored. Use a function or a getter instead of a variable to avoid "Stale Data" bugs.

### 3. Who "Owns" the decision?
- If two classes both have logic to determine X, which one is right?
- **Rule**: Centralize the decision logic. Other components should query the "Owner" rather than re-implementing the logic.

### 4. What is the "Lifecycle" alignment?
- Does this state outlive its consumer? Or does it die with it?
- **Rule**: Place the state in a container that matches its natural lifecycle (e.g., Global Redux for app-wide state, Local State for UI-only transient data).

## Proof of Value (Case Studies)

### Anti-Pattern: Fragmented Thresholds
- **Previous State**: Memory thresholds were hardcoded in `AdaptiveOverlaySystem.kt`, `AndroidMemoryMonitor.kt`, and `AdaptiveOverlayFallback.kt`.
- **Result**: Inconsistent behavior and "Shadow Budgets" that were hard to debug.
- **Solution**: Centralized everything in `MemoryPressureLevel.kt` (The Single Source of Truth).

### Anti-Pattern: Shadow Redux State
- **Previous State**: Small, isolated state containers (`GpsStatus.kt`, `WaypointState.kt`) lived in separate files.
- **Result**: Fragmented state tree and excessive boilerplate.
- **Solution**: Consolidated into `MapState.kt` to ensure a cohesive view of the "World State."

### Anti-Pattern: Taxonomic Singularity (Shadow Vocabulary)
- **Previous State**: `AdaptiveOverlaySystem` utilized the term "Budget" to define its absolute Max **Capacity** (e.g. 528 overlays). Downstream, `OverlayCoordinator` printed "Global Overlay Budget" to describe its currently active rendered **Usage** (e.g. 29 overlays).
- **Result**: Conflicting taxonomy obscured logcat analysis, creating the illusion that the dynamic capacity governor was malfunctioning or aggressively dropping SLA budgets.
- **Solution**: Decouple the terminology. Rename the coordinator's metric to "Global Overlay Usage". The SSOT for terminology is equally as important as the SSOT for memory state!

- **Solution**: Implement Bidirectional Synchronization. Redux MUST be the Source of Truth for the map camera. If the store says the center is X, the map MUST move to X reactively.

### Anti-Pattern: Fragmented Resource Budgets
- **Previous State**: Individual overlay managers (`PGSpotOverlayManager.kt`) hardcoded their own cache sizes or initialized redundant `AdaptiveOverlaySystem` monitors.
- **Result**: Conflicting resource limits and high GC pressure in high-density regions because the local "truth" (e.g. 50 spots) contradicted the system's actual capacity (e.g. 400 spots).
- **Solution**: Centralize budget distribution in `OverlayCoordinator.kt`. Managers MUST query the single `AdaptiveOverlaySystem` instance provided by the coordinator and resize their internal caches dynamically via `onOverlayBudgetChanged`.

## Diagnostic Checklist
- [ ] Have I identified the **Owner** of this information?
- [ ] Is this value **Purely Derived**? (If so, use a `val` getter).
- [ ] If I change this value in one place, will it "break" the other "Source of Truth"? (If yes, you have two sources of truth—delete one).
- [ ] Am I "Shadowing" a system-level property? (e.g., storing a local `isGpsOn` instead of querying the system status).
