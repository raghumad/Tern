---
name: Ranked Spatial Exclusion (RSE)
description: Patterns for managing concurrent spatial features to prevent visual clutter and ensure glanceable situational awareness.
---

# 🛰️ Ranked Spatial Exclusion (RSE)

Maintain situational awareness in high-density map environments by enforcing a strict hierarchy of visual detail based on screen-space density rather than individual item importance.

## 🚀 The Protocol

When implementing or refactoring map overlays (Waypoints, Airspaces, Hazards), ensure the design follows the **Ranked Spatial Exclusion** protocol:

1.  **Tier the Information**:
    - **Tier 1 (Active)**: Immediate tactical goals (Scale 1.0, Labels ON).
    - **Tier 2 (Path)**: Features on the current trajectory (Scale 0.8, Labels ON/OFF based on collision).
    - **Tier 3 (Hazard)**: Active threats (Scale 1.0, Pulsing, Labels OFF).
    - **Tier 4 (Context)**: Background data (Scale 0.2, "Pin-pricks", Labels OFF).

2.  **Enforce Density Budgeting**:
    - Use a **Screen-Space Lattice** (Grid) to arbitrate overlaps.
    - No two labels should ever occupy the same `48dp` cell. Use **Leader Lines** to offset if necessary.

3.  **Trajectory-First Z-Indexing**:
    - The **Active Flight Path** must always be visible. Correct drawing order:
        1. Contextual Map
        2. Tier 4 Pin-pricks
        3. Route Segments (Legs)
        4. Tier 1-3 Icons/Labels

## ⚠️ High-Risk Patterns (Audit Checklist)

- [ ] **Distributed Autonomy**: Avoid letting markers decide their own visibility.
- [ ] **Binary Priority**: Stop using simple `Boolean` flags for "priority." Use a `RankingTier` (1-4).
- [ ] **Scale-Invariance**: Ensure directional arrows and decorators scale with the visible segment length.
- [ ] **Label Collision**: Do not allow labels to overlap. If they do, they must either minimize to pin-pricks or use leader lines.

## 🛠️ Design Patterns

### Leader Line Offset
When a label must be visible but its anchor point is crowded:
```kotlin
// Offset label by 20dp at 45 degrees
val offsetVector = Offset(20.dp, -20.dp)
Canvas.drawLine(anchor, anchor + offsetVector)
DrawLabel(anchor + offsetVector)
```

### Grid-Based Culling
Before rendering, check cell occupancy:
```kotlin
if (grid.isOccupied(screenX, screenY, tier = 1)) {
    thisMarker.minimizeToPinPrick()
}
```

---
> [!TIP]
> **If the Pilot has to squint, the UI has failed.** RSE ensures that the most important thing is always the most obvious thing.
