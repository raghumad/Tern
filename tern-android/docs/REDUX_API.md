# Redux API Documentation

## Overview

The Tern Android app uses Redux for state management with aviation-grade safety standards. This document provides comprehensive API documentation for all Redux actions and state structures.

## Core Concepts

- **Single Source of Truth**: All application state is managed through Redux
- **Predictable State Updates**: Actions → Reducers → State changes
- **Immutability**: State is never mutated directly
- **Performance Batching**: <10 Redux dispatches/sec target

## State Structure

### MapState (Root State)

```kotlin
data class MapState(
    // Map viewport state
    val rotation: Float = 0f,
    val center: GeoPoint? = null,
    val zoom: Double = MapConstants.DEFAULT_ZOOM_LEVEL,

    // Location state
    val isLocationReady: Boolean = false,
    val userLocation: GeoPoint? = null,
    val gpsStatus: GpsStatus = GpsStatus.INITIAL,

    // Permission state
    val hasLocationPermission: Boolean = false,
    val permissionRequested: Boolean = false,

    // Overlay state
    val overlayState: OverlayState = OverlayState(),

    // Weather state
    val weatherState: WeatherState = WeatherState(),

    // Loading states
    val isLoadingAirspaces: Boolean = false,
    val isLoadingPGSpots: Boolean = false,

    // Cache state
    val currentCountryCode: String? = null,
    val airspaceCountries: Set<String> = emptySet(),
    val pgSpotCountries: Set<String> = emptySet(),

    // Configuration
    val mapStyle: String = "terrain",
    val compassVisible: Boolean = true,

    // Settings & preferences
    val settingsState: SettingsState = SettingsState(),
    val userPreferences: UserPreferencesState = UserPreferencesState(),
    val currentFlightMode: FlightMode = FlightMode.GROUND,
    val adaptiveLayout: AdaptiveLayoutConfig = AdaptiveLayoutConfig(),

    // Route state
    val routes: List<Route> = emptyList(),
    val selectedWaypoint: WaypointSelection? = null,

    // Sensor & flight data
    val sensorState: SensorState = SensorState(),
    val currentFlightData: FlightData? = null,
    val flightComputerData: FlightComputerData? = null,
    val flightMetrics: FlightMetrics? = null
)
```

### OverlayState

```kotlin
data class OverlayState(
    val airspaces: OverlayConfig = OverlayConfig(enabled = true),
    val pgSpots: OverlayConfig = OverlayConfig(enabled = true),
    val routes: OverlayConfig = OverlayConfig(enabled = true)
)

data class OverlayConfig(
    val enabled: Boolean = false,
    val opacity: Float = OverlayConstants.DEFAULT_OVERLAY_OPACITY,
    val showLabels: Boolean = true,
    val filterRadiusMiles: Double = OverlayConstants.DEFAULT_FILTER_RADIUS_MILES
)
```

## Redux Actions

### Permission & Location Actions

#### `RequestLocationPermission`
Requests location permission from the user.
- **Type**: `object RequestLocationPermission : MapAction()`
- **Parameters**: None
- **State Changes**: Sets `permissionRequested = true`

#### `UpdateLocationPermission`
Updates the location permission status.
- **Type**: `data class UpdateLocationPermission(val granted: Boolean) : MapAction()`
- **Parameters**:
  - `granted: Boolean` - Whether permission was granted
- **State Changes**: Updates `hasLocationPermission` and `permissionRequested`

#### `UpdateUserLocation`
Updates the user's current location.
- **Type**: `data class UpdateUserLocation(val location: GeoPoint?) : MapAction()`
- **Parameters**:
  - `location: GeoPoint?` - Current GPS location (null if unavailable)
- **State Changes**: Updates `userLocation`

#### `SetLocationReady`
Indicates whether the location system is ready for aviation operations.
- **Type**: `data class SetLocationReady(val ready: Boolean) : MapAction()`
- **Parameters**:
  - `ready: Boolean` - GPS validation status
- **State Changes**: Updates `isLocationReady`

#### `UpdateGpsStatus`
Updates GPS acquisition status.
- **Type**: `data class UpdateGpsStatus(val status: GpsStatus) : MapAction()`
- **Parameters**:
  - `status: GpsStatus` - Current GPS status
- **State Changes**: Updates `gpsStatus`

#### `RetryGpsAcquisition`
Retries GPS acquisition after failure.
- **Type**: `object RetryGpsAcquisition : MapAction()`
- **Parameters**: None
- **State Changes**: Resets GPS status to ACQUIRING, clears location

### Map Viewport Actions

#### `UpdateRotation`
Updates map rotation angle.
- **Type**: `data class UpdateRotation(val rotation: Float) : MapAction()`
- **Parameters**:
  - `rotation: Float` - Rotation angle in degrees
- **State Changes**: Updates `rotation`

#### `UpdateCenter`
Updates map center position.
- **Type**: `data class UpdateCenter(val center: GeoPoint) : MapAction()`
- **Parameters**:
  - `center: GeoPoint` - New center coordinates
- **State Changes**: Updates `center`

#### `UpdateZoom`
Updates map zoom level.
- **Type**: `data class UpdateZoom(val zoom: Double) : MapAction()`
- **Parameters**:
  - `zoom: Double` - Zoom level (higher = more zoomed in)
- **State Changes**: Updates `zoom`

#### `UpdateMapMovement`
Combined action for efficient map movement updates.
- **Type**: `data class UpdateMapMovement(val rotation: Float? = null, val center: GeoPoint? = null, val zoom: Double? = null) : MapAction()`
- **Parameters**:
  - `rotation: Float?` - Optional rotation update
  - `center: GeoPoint?` - Optional center update
  - `zoom: Double?` - Optional zoom update
- **State Changes**: Updates specified viewport properties

### Overlay Management Actions

#### `SetOverlayEnabled`
Enables/disables an overlay type.
- **Type**: `data class SetOverlayEnabled(val type: OverlayType, val enabled: Boolean) : MapAction()`
- **Parameters**:
  - `type: OverlayType` - AIRSPACE, PG_SPOTS, or ROUTES
  - `enabled: Boolean` - Whether to show the overlay
- **State Changes**: Updates overlay enabled status in `overlayState`

#### `UpdateOverlayConfig`
Updates overlay configuration.
- **Type**: `data class UpdateOverlayConfig(val type: OverlayType, val config: OverlayConfig) : MapAction()`
- **Parameters**:
  - `type: OverlayType` - Overlay type to configure
  - `config: OverlayConfig` - New configuration
- **State Changes**: Updates overlay configuration in `overlayState`

### Route Management Actions

#### `AddRoute`
Adds a new route to the application.
- **Type**: `data class AddRoute(val route: Route) : MapAction()`
- **Parameters**:
  - `route: Route` - Route to add
- **State Changes**: Adds route to `routes` list (limited to MAX_ROUTES)

#### `RemoveRoute`
Removes a route from the application.
- **Type**: `data class RemoveRoute(val routeId: String) : MapAction()`
- **Parameters**:
  - `routeId: String` - ID of route to remove
- **State Changes**: Removes route from `routes`, clears selection if affected

#### `UpdateRoute`
Updates an existing route.
- **Type**: `data class UpdateRoute(val route: Route) : MapAction()`
- **Parameters**:
  - `route: Route` - Updated route data
- **State Changes**: Replaces route in `routes` list

#### `ClearAllRoutes`
Removes all routes from the application.
- **Type**: `object ClearAllRoutes : MapAction()`
- **Parameters**: None
- **State Changes**: Clears `routes` list and `selectedWaypoint`

### Waypoint Management Actions

#### `AddWaypointToRoute`
Adds a waypoint to an existing route.
- **Type**: `data class AddWaypointToRoute(val routeId: String, val lat: Double, val lon: Double, val type: Waypoint.Type = Waypoint.Type.TURNPOINT, val label: String? = null) : MapAction()`
- **Parameters**:
  - `routeId: String` - Route to add waypoint to
  - `lat: Double` - Latitude coordinate
  - `lon: Double` - Longitude coordinate
  - `type: Waypoint.Type` - Waypoint type (default: TURNPOINT)
  - `label: String?` - Optional waypoint label
- **State Changes**: Adds waypoint to specified route

#### `RemoveWaypoint`
Removes a waypoint from a route.
- **Type**: `data class RemoveWaypoint(val routeId: String, val waypointId: String) : MapAction()`
- **Parameters**:
  - `routeId: String` - Route containing the waypoint
  - `waypointId: String` - Waypoint to remove
- **State Changes**: Removes waypoint from route, clears selection if affected

#### `UpdateWaypoint`
Updates waypoint properties.
- **Type**: `data class UpdateWaypoint(val routeId: String, val waypointId: String, val lat: Double? = null, val lon: Double? = null, val type: Waypoint.Type? = null, val label: String? = null) : MapAction()`
- **Parameters**:
  - `routeId: String` - Route containing the waypoint
  - `waypointId: String` - Waypoint to update
  - `lat: Double?` - New latitude (optional)
  - `lon: Double?` - New longitude (optional)
  - `type: Waypoint.Type?` - New waypoint type (optional)
  - `label: String?` - New label (optional)
- **State Changes**: Updates waypoint properties

### Interactive Editing Actions

#### `SelectWaypoint`
Selects a waypoint for editing.
- **Type**: `data class SelectWaypoint(val routeId: String, val waypointId: String) : MapAction()`
- **Parameters**:
  - `routeId: String` - Route containing the waypoint
  - `waypointId: String` - Waypoint to select
- **State Changes**: Sets `selectedWaypoint`

#### `DeselectWaypoint`
Clears waypoint selection.
- **Type**: `object DeselectWaypoint : MapAction()`
- **Parameters**: None
- **State Changes**: Sets `selectedWaypoint = null`

#### `StartWaypointDrag`
Begins waypoint drag operation.
- **Type**: `data class StartWaypointDrag(val routeId: String, val waypointId: String) : MapAction()`
- **Parameters**:
  - `routeId: String` - Route containing the waypoint
  - `waypointId: String` - Waypoint to drag
- **State Changes**: Sets waypoint as dragging in selection

#### `UpdateWaypointDrag`
Updates waypoint position during drag.
- **Type**: `data class UpdateWaypointDrag(val lat: Double, val lon: Double) : MapAction()`
- **Parameters**:
  - `lat: Double` - New latitude during drag
  - `lon: Double` - New longitude during drag
- **State Changes**: Updates waypoint position in route

#### `EndWaypointDrag`
Completes waypoint drag operation.
- **Type**: `object EndWaypointDrag : MapAction()`
- **Parameters**: None
- **State Changes**: Clears dragging state in selection

## Weather Actions

### `FetchWeatherForPGSpot`
Initiates weather data fetching for a PG spot.
- **Type**: `data class FetchWeatherForPGSpot(val pgSpotId: String) : WeatherActions()`
- **Parameters**:
  - `pgSpotId: String` - PG spot identifier
- **State Changes**: Adds to `fetchingSpots`

### `WeatherFetched`
Handles successful weather data retrieval.
- **Type**: `data class WeatherFetched(val pgSpotId: String, val forecast: WeatherForecast?) : WeatherActions()`
- **Parameters**:
  - `pgSpotId: String` - PG spot identifier
  - `forecast: WeatherForecast?` - Retrieved forecast data
- **State Changes**: Updates `spotWeathers`, removes from `fetchingSpots`

### `WeatherFetchError`
Handles weather data fetch failures.
- **Type**: `data class WeatherFetchError(val pgSpotId: String, val error: Throwable) : WeatherActions()`
- **Parameters**:
  - `pgSpotId: String` - PG spot identifier
  - `error: Throwable` - Error that occurred
- **State Changes**: Updates `errors`, removes from `fetchingSpots`

## Usage Examples

### Creating and Managing Routes

```kotlin
// Create a new route
val route = Route(name = "Cross-country Route")
store.dispatch(MapAction.AddRoute(route))

// Add waypoints to route
store.dispatch(MapAction.AddWaypointToRoute(
    routeId = route.id,
    lat = 46.5,
    lon = 8.2,
    type = Waypoint.Type.TURNPOINT,
    label = "Start"
))

// Select waypoint for editing
store.dispatch(MapAction.SelectWaypoint(route.id, waypointId))

// Update waypoint position
store.dispatch(MapAction.UpdateWaypoint(
    routeId = route.id,
    waypointId = waypointId,
    lat = 46.6,
    lon = 8.3
))
```

### Managing Overlays

```kotlin
// Enable airspace overlay
store.dispatch(MapAction.SetOverlayEnabled(OverlayType.AIRSPACE, true))

// Update overlay configuration
val config = OverlayConfig(
    enabled = true,
    opacity = 0.8f,
    showLabels = true,
    filterRadiusMiles = 200.0
)
store.dispatch(MapAction.UpdateOverlayConfig(OverlayType.AIRSPACE, config))
```

### Location Management

```kotlin
// Update user location
val location = GeoPoint(46.5, 8.2)
store.dispatch(MapAction.UpdateUserLocation(location))

// Set location ready for aviation operations
store.dispatch(MapAction.SetLocationReady(true))
```

## Performance Considerations

- **Action Batching**: Redux store automatically batches actions within 100ms windows
- **Dispatch Limits**: Target <10 dispatches per second for smooth performance
- **Memory Limits**: Maintain <75% heap usage
- **State Immutability**: All state updates create new instances

## Aviation Safety Standards

- **GPS Validation**: All aviation operations require validated GPS fix
- **Visual Continuity**: No jarring visual changes during flight
- **Progressive Enhancement**: App works at all sensor capability levels
- **Error Recovery**: Graceful degradation without compromising safety
