---
name: Aviation-Grade UX Standards
description: Core principles for designing user interfaces that are safe, reliable, and usable in high-stress aviation environments.
---

# ✈️ Aviation-Grade UX Standards

Tern is not a standard consumer map application—it is a life-critical flight instrument strapped to a pilot's leg flying at 10,000 feet. Every UI decision must be measured against the harsh reality of the cockpit environment: **direct glare, extreme vibration, cold hands (gloves), and micro-second attention spans.**

When implementing a new Composable, dialog, or map overlay, you must rigidly adhere to these four pillars of Aviation UX:

## ☀️ 1. Absolute Contrast & Glare Resistance
**The Environment:** Pilots fly in full, unfiltered sunlight wearing polarized lenses.
**The Law:** 
- Never rely on subtle shades of gray or low-opacity blurs to convey critical information.
- Critical text (Altitude, Waypoint Names, Countdowns) must utilize absolute strict contrast ratios (e.g., stark white on deep black, or high-visibility yellow).
- UI elements must cast heavy drop-shadows or outlines (`drawText` stroke) to ensure they stand out against chaotic multi-colored satellite maps.

## 🧠 2. Zero Cognitive Overhead
**The Environment:** A pilot's brain is 90% saturated flying the wing, managing pitch, and looking out for other aircraft. They only have 10% remaining for the flight instrument.
**The Law:**
- **No Mental Math:** Never show "15:00 UTC" if the pilot needs "Time to Goal". Do the math for them. Show "+5 min to arrival".
- **Glanceability:** If a pilot cannot understand the entire screen state in **0.5 seconds**, the design has failed. Use stark iconography, color-coded alerts (Red = Danger, Green = Safe), and massive typography for primary metrics.

## 📴 3. Bulletproof Offline Resilience
**The Environment:** There is zero cellular coverage in the remote Alps or high above the cloud base.
**The Law:**
- **Zero Spinners:** Never show an infinite loading spinner for a network request during flight. If data isn't cached natively in FlexBuffers, fail immediately and gracefully showing the last known state with a clear "Offline" indicator.
- **Predictable Degradation:** The UI layout must never shift, collapse, or break just because an API request yielded a 404 or a timeout.

## ✋ 4. Fat-Finger Tactility
**The Environment:** Pilots are wearing thick winter gloves, subject to turbulence, trying to tap a 6-inch vibrating glass screen.
**The Law:**
- **Massive Hitboxes:** Every interactive button, slider, or map element MUST have an exaggerated touch target (minimum `48dp`x`48dp`, preferably `64dp` for core actions).
- **Haptic Confirmation:** Any state-mutating UI interaction must trigger a definitive device-level haptic vibration (`HapticFeedbackType.LongPress` or `Android `VibrationEffect`) to physically confirm the action succeeded without the pilot having to visually verify the screen.
- **Debounce Everything:** Protect against accidental double-taps induced by turbulence. Every UI action must be debounced natively.

## 🕹️ 5. Muscle Memory & Aviation Standardization
**The Environment:** Pilots rely on instinctive movements gained from years of using dedicated flight instruments. If they have to "think" about where a button is, they aren't looking at their wing or their surroundings.
**The Law:**
- **The "Two-Tap" Rule**: Core in-flight functionality (Waypoint change, Wind check, Thermal assistant) must never be more than two taps away from the main map.
- **Harmonize with Garmin/Naviter**: Do not reinvent the wheel for standard aviation symbols. Use the ISO-standard "H" for Goal, standard wind barbs, and the familiar cross-hair or arrow paradigms found on Flytec/Garmin units.
- **Predictability Over Innovation**: If a pilot anticipates that a "Back" swipe takes them to the map, it must *always* take them to the map. Never nest deep, inconsistent menu hierarchies that trap the user.

## 🧭 6. The "Pilot-First" Intuition
**The Environment:** Pilots are aviation experts, not software engineers. They understand "AOA" and "Glide Ratio," not "JSON" or "Syncing State."
**The Law:**
- **Speak Aviation, Not Tech**: Use "Flight Log" instead of "History," "Waypoints" instead of "Markers," and "Satellite Connectivity" instead of "Socket Status."
- **Standard UI Metaphors**: Leverage Apple/iOS design simplicity—clean lines, obvious affordances (buttons that look like buttons), and meaningful animations that guide the eye to the new state without disorienting the pilot. Avoid "programmer-style" UIs that over-expose configuration.
