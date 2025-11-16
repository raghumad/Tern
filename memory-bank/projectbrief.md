# Project Brief

Tern is a paragliding flight deck application designed to assist paragliders in flight planning, navigation, and execution. The app provides cross-platform support (iOS SwiftUI and Android Compose/Kotlin) with essential flight deck features including airspace management, hotspot identification, weather forecasting, and route planning.

## Purpose
- **Aviation-grade safety standards** for paragliders from student to competition pilots
- **Competition-ready features** supporting Red Bull X-Alps and similar events
- **Offline resilience** with comprehensive caching for remote operations
- **Progression enhancement** working at all sensor capability levels

## Core Goals
- Provide comprehensive flight deck functionality for paragliders
- Ensure cross-platform consistency between iOS and Android
- Maintain high safety standards by incorporating airspace restrictions and weather data
- Deliver intuitive user experience for flight planning and in-flight navigation
- Support smooth performance and visual continuity during aviation operations

## Scope
- Airspace visualization and alerts with aviation priorities
- Hotspot mapping and thermal sources correlation
- NWS and OpenMeteo weather integration
- Competition-ready route planning with spatial indexing
- Route caching and persistence across app restarts
- 10-route limit with distance-based spatial filtering
- Interactive waypoint editing and route management
- Progressive zoning overlay system (CORE → NEAR → MID → FAR → EXTREME)

## Key Files and Structure
- iOS app in Tern/ directory (SwiftUI)
- Android app in tern-android/ directory (Kotlin, Redux-first)
- Shared models for data structures (airspaces, weather, routes)
- Cross-platform models for weather, routes, waypoints
