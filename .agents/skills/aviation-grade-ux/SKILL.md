---
name: Aviation-Grade UX Standards
description: Core principles for designing user interfaces that are safe, reliable, and usable in high-stress aviation environments.
---

# ✈️ Aviation-Grade UX Standards

Tern is not a standard consumer map application—it is a life-critical flight instrument strapped to a pilot's leg flying at 10,000 feet. Every UI decision must be measured against the harsh reality of the cockpit environment: **direct glare, extreme vibration, cold hands (gloves), and micro-second attention spans.**

When implementing a new Composable, dialog, or map overlay, you must rigidly adhere to these pillars of Aviation UX:

## ☀️ 1. Absolute Contrast & Glare Resistance
- **Absolute Contrast**: Never rely on subtle shades of gray. Use stark contrast ratios (e.g., stark white on deep black/AeroSlate, or high-visibility yellow).
- **Glanceability (0.5s Rule)**: If a pilot cannot understand the entire screen state in **0.5 seconds**, the design has failed. Use stark iconography and massive primary metrics.
- **Micro-Animations**: Use flashing/strobing elements (1Hz) for immediate tactical threats (e.g., Lightning Bolts) to penetrate cognitive saturation.

## 🧭 2. Trajectory-Aware Situational Awareness (SSA/TEA)
Aviation UI must adapt to the pilot's mission phase.
- **SSA (Strategic Situational Awareness)**: A collapsed, low-density mode for long-range planning. Critical risks must be aggregated into high-contrast "tokens" (e.g., `! STORM RISK`).
- **TEA (Tactical Execution Analysis)**: An expanded, high-density mode for immediate maneuver analysis. Provides per-waypoint telemetry, coordinates, and fine-grained hazards.
- **Strategic Auto-Minimize**: The UI must automatically transition to SSA mode upon trajectory generation (e.g., adding waypoints) or strategic zooming, ensuring the map remains the primary source of truth.

## 🧠 3. Zero Cognitive Overhead
- **No Mental Math**: Never show "15:00 UTC" if the pilot needs "Time to Goal". Show "+5 min to arrival".
- **Speak Aviation, Not Tech**: Use "Flight Log" instead of "History," "Waypoints" instead of "Markers," and "Satellite Connectivity" instead of "Socket Status."
- **Predictable Degradation**: Zero spinners. If data isn't cached, show the last known state with a clear "Offline" indicator.

## ✋ 4. Fat-Finger Tactility
- **Hitboxes**: Minimum `48dp`x`48dp`, preferably `64dp` for core in-flight actions.
- **Haptic Confirmation**: Any state-mutating UI interaction must trigger a definitive device-level haptic vibration to confirm success without visual verification.
- **Handedness Awareness**: Control placement must respect the pilot's preferred hand (Left/Right) to minimize screen occlusion.

## 🛠️ Verification Checklist
- [ ] Can the UI be understood in < 0.5s in direct sunlight?
- [ ] Does the panel auto-minimize to SSA mode when a route is active?
- [ ] Are tactical hazards (Lightning) highlighted with micro-animations?
- [ ] Are touch targets optimized for gloved hands (min 48dp)?
- [ ] Does the UI speak "Aviation" or "Programmer"?
