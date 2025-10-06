# 🗂️ Redux Implementation Patterns

## 📋 Overview
Simple, consistent patterns for Redux state management in Tern.

## 🎯 Basic Redux Pattern

### Action (What Happened)
```kotlin
sealed class MapAction {
    // Location actions
    data class UpdateUserLocation(val location: GeoPoint?) : MapAction()
    data class SetLocationReady(val ready: Boolean) : MapAction()

    // Overlay actions
    data class SetOverlayEnabled(val type: OverlayType, val enabled: Boolean) : MapAction()

    // Map view actions
    data class UpdateMapMovement(val center: GeoPoint?, val zoom: Double?) : MapAction()
}
```

### State (Current Condition)
```kotlin
data class MapState(
    // Location state
    val userLocation: GeoPoint? = null,
    val isLocationReady: Boolean = false,

    // Overlay state
    val overlayState: OverlayState = OverlayState(),

    // Map view state
    val center: GeoPoint? = null,
    val zoom: Double = 8.0,
    val rotation: Float = 0f
)
```

### Reducer (How State Changes)
```kotlin
fun mapReducer(state: MapState, action: MapAction): MapState {
    return when (action) {
        is MapAction.UpdateUserLocation -> {
            state.copy(userLocation = action.location)
        }
        is MapAction.SetLocationReady -> {
            state.copy(isLocationReady = action.ready)
        }
        is MapAction.SetOverlayEnabled -> {
            // Update specific overlay type
            val newOverlayState = when (action.type) {
                OverlayType.AIRSPACE -> state.overlayState.copy(airspaces = state.overlayState.airspaces.copy(enabled = action.enabled))
                OverlayType.PG_SPOTS -> state.overlayState.copy(pgSpots = state.overlayState.pgSpots.copy(enabled = action.enabled))
                // ... other overlay types
            }
            state.copy(overlayState = newOverlayState)
        }
        // ... handle other actions
    }
}
```

## 🏪 Store Pattern

### MapStore Implementation
```kotlin
class MapStore : ViewModel() {
    private val _state = MutableStateFlow(MapState())
    val state = _state.asStateFlow()

    fun dispatch(action: MapAction) {
        _state.value = mapReducer(_state.value, action)
    }

    // Helper methods for common actions
    fun updateLocation(location: GeoPoint?) =
        dispatch(MapAction.UpdateUserLocation(location))

    fun setLocationReady(ready: Boolean) =
        dispatch(MapAction.SetLocationReady(ready))
}
```

## 🎨 Component Pattern

### Observing State Changes
```kotlin
@Composable
fun MyComponent(store: MapStore = viewModel()) {
    // ✅ CORRECT: Observe Redux state
    val state by store.state.collectAsState()
    val isReady = state.isLocationReady
    val location = state.userLocation

    // UI automatically updates when state changes
    Text(if (isReady) "GPS Ready: $location" else "Acquiring GPS...")

    // ❌ WRONG: Don't manage state in components
    // var localState = remember { mutableStateOf(false) }
}
```

### Dispatching Actions
```kotlin
@Composable
fun LocationButton(store: MapStore = viewModel()) {
    // ✅ CORRECT: Dispatch Redux actions
    Button(onClick = {
        store.dispatch(MapAction.RequestLocationPermission)
    }) {
        Text("Request GPS")
    }

    // ❌ WRONG: Direct state manipulation
    // Button(onClick = { someViewModel.directStateChange() })
}
```

## ⚡ Performance Optimization

### Batching for Efficiency
```kotlin
// ✅ GOOD: Batch multiple actions
fun updateMapMovement(center: GeoPoint?, zoom: Double?, rotation: Float?) {
    dispatch(MapAction.UpdateMapMovement(center, zoom, rotation))
}

// ❌ BAD: Multiple separate dispatches
fun updateMapMovementBad(center: GeoPoint?, zoom: Double?, rotation: Float?) {
    center?.let { dispatch(MapAction.UpdateCenter(it)) }
    zoom?.let { dispatch(MapAction.UpdateZoom(it)) }
    rotation?.let { dispatch(MapAction.UpdateRotation(it)) }
}
```

## 🚦 Best Practices

### ✅ DO
- Use Redux actions for ALL state changes
- Observe state with `.collectAsState()`
- Batch related actions together
- Keep reducers pure functions
- Use helper methods on Store for common actions

### ❌ DON'T
- Manage state directly in components
- Call overlay manager methods from UI
- Use mutable state outside Redux
- Have multiple sources of truth
- Dispatch actions from outside Redux flow

---

## 💡 Simple Redux Checklist

**Before Writing Code:**
- [ ] Does this need to change application state?
- [ ] Can I represent this as a Redux action?
- [ ] Will this trigger UI updates through state observation?

**After Writing Code:**
- [ ] Does the component only read from Redux state?
- [ ] Are all state changes dispatched as actions?
- [ ] Is the reducer logic clear and testable?
- [ ] Do related actions get batched together?

This pattern ensures consistent, predictable state management throughout the application.