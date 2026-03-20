# Development Guide

This guide outlines the mandatory development patterns and standards for the Tern project.

## 🏗️ Architecture Principles (Non-Negotiable)

### Core Philosophy
*   **Offline-First**: Critical flight data (Airspaces, Weather, Routes) must be available without internet.
*   **Safety First**: Smooth transitions, predictable performance, offline resilience.
*   **Redux Single Source of Truth**: All domain state lives in Redux. Use batching (100ms) for high-frequency updates.

### Hybrid Development Pattern
1.  **Phase 1 (Development)**: Use ViewModel-based state management for rapid prototyping.
2.  **Phase 2 (Polish)**: Migrate to Redux pattern with overlay managers extending `BaseOverlayManager` before release.

## 🪂 Domain-Specific Standards

### Airspace Priority System
1.  **🚨 Level 1 (Danger)**: Military zones, prohibited areas, TFRs.
2.  **⚠️ Level 2 (High)**: Training areas, competition zones.
3.  **📍 Level 3 (Moderate)**: Controlled airspace (TMA, CTR).
4.  **ℹ️ Level 4 (Low)**: Airways, navigation aids.

### Coordinate & Data Precision
*   **Coordinate Swap**: Always convert GeoJSON `[Lon, Lat]` to OSMDroid `(Lat, Lon)`.
*   **Unit Convention**: Miles for User UI, Meters for Internal Engine (OSMDroid).
*   **Spatial Indexing**: Mandatory use of Hilbert Curve for proximity-based feature loading. [Skill: Spatial Efficiency](file:///home/raghu/src/Tern/.agents/skills/spatial-efficiency/SKILL.md)

## 📱 Hardware Integration

### Sensor Matrix (Pixel Series)
| Device Series | Barometer | Compass | Accelerometer |
| --- | --- | --- | --- |
| Pixel 1-3 | ✅ | ✅ | ✅ |
| Pixel 4-5 | ✅ | ✅ | ✅ |
| Pixel 6-9 | ❌ | ⚠️ Partial | ✅ |

### Graceful Degradation
Architecture must handle the absence of a barometer by falling back to Kalman-filtered GPS altitude.

## ⚡ Completion Criteria
- [ ] **Technical**: Zero compilation warnings, 100% Redux compliance (post-Phase 1), <75% memory usage.
- [ ] **Verification**: Full suite pass via `./gradlew testAll`.
- [ ] **User Story**: BDD-style tests must include a formal story from the pilot's perspective.
