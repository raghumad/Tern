# Route Architecture Implementation Plan

## 🎯 Problem Statement

Current route system uses global waypoint storage, making it impossible to:
- Manage multiple routes simultaneously
- Control route visibility independently
- Provide real-time editing feedback
- Support route-specific metadata and styling

## 🏗️ Target Architecture

- **Route-centric**: Routes as primary data structure
- **Reactive**: StateFlow-based real-time updates
- **Multi-route**: Support for multiple routes with independent visibility
- **Persistent**: Route storage with metadata

## 📋 Implementation Checklist

### Phase 1: Documentation & Architecture (2 items)
- [ ] Update ROUTE_PLANNER_SPEC.md with new architecture
- [ ] Update ARCHITECTURE_DECISIONS.md with design rationale

### Phase 2: Code Cleanup Plan (2 items)
- [ ] Identify files to modify (WaypointStore, RouteOverlayManager, etc.)
- [ ] Identify files to create (Route.kt, RouteStore.kt, RouteColor.kt)

### Phase 3: Core Implementation (3 items)
- [ ] Create Route model with metadata and visibility
- [ ] Create RouteStore with StateFlow management
- [ ] Update RouteOverlay for route-specific styling

### Phase 4: Map Integration (2 items)
- [ ] Rewrite RouteOverlayManager for route-centric rendering
- [ ] Update WaypointStore integration for backward compatibility

### Phase 5: Visual Polish (2 items)
- [ ] Implement multi-route styling and colors
- [ ] Add route management UI integration points

### Phase 6: Real-Time Reactivity (2 items)
- [ ] Implement instant route editing feedback
- [ ] Performance optimization for multiple routes

### Phase 7: Testing & Validation (2 items)
- [ ] Integration testing of complete workflows
- [ ] User experience validation

## 🗂️ File Changes Summary

### Files to Create
- `Route.kt` - Route data model
- `RouteStore.kt` - Route state management
- `RouteColor.kt` - Route styling options
- `RouteManagerUI.kt` - Route visibility controls

### Files to Modify
- `RouteOverlayManager.kt` - Rewrite for route-centric design
- `RouteOverlay.kt` - Update to work with Route objects
- `WaypointStore.kt` - Add route context methods
- `WaypointList.kt` - Update for route context

## ⚡ Key Technical Patterns

### Route Model
```kotlin
data class Route(
    val id: String,
    val name: String,
    val waypoints: List<Waypoint>,
    val isVisible: Boolean = true,
    val color: RouteColor = RouteColor.BLUE
)
```

### RouteStore Pattern
```kotlin
object RouteStore {
    val routes: StateFlow<List<Route>>
    fun updateRoute(routeId: String, updateBlock: (Route) -> Route)
    fun setRouteVisibility(routeId: String, visible: Boolean)
}
```

### Reactive Rendering
```kotlin
// Map reacts to route changes instantly
RouteStore.routes.collect { routes ->
    renderVisibleRoutes(routes.filter { it.isVisible })
}
```

## 🎯 Success Criteria
- [ ] Multiple routes can be displayed simultaneously
- [ ] Route editing provides instant visual feedback
- [ ] Route visibility can be controlled independently
- [ ] Real-time updates work smoothly during waypoint dragging
- [ ] No performance degradation with multiple routes
- [ ] Backward compatibility maintained during transition

## 🚀 Usage Instructions
Provide this checklist to orchestrator mode for systematic implementation.
Each phase builds on previous work, ensuring stable progression.