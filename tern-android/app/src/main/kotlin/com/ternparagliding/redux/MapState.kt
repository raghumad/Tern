package com.ternparagliding.redux

import org.osmdroid.util.GeoPoint

import com.ternparagliding.mezulla.redux.PeerState
import com.ternparagliding.model.Route
import com.ternparagliding.model.TernBoundingBox
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Global state for map functionality using Redux pattern
 */
data class MapState(
    // Map viewport state
    val rotation: Float = 0f,
    val center: GeoPoint? = null,
    val zoom: Double = MapConstants.DEFAULT_ZOOM_LEVEL,
    val pendingBoundingBox: TernBoundingBox? = null,
    val isRoutePanelExpanded: Boolean = true, // Strategic Auto-Minimize

    // Location state
    val isLocationReady: Boolean = false,
    val userLocation: GeoPoint? = null,
    val gpsStatus: GpsStatus = GpsStatus.INITIAL,

    // Permission state
    val hasLocationPermission: Boolean = false,
    val permissionRequested: Boolean = false,

    // Overlay state - modular overlay management
    val overlayState: OverlayState = OverlayState(),
    val hasAirspaceCollision: Boolean = false,

    // Weather state for PG spots
    val weatherState: WeatherState = WeatherState(),

    // Loading state
    val isLoadingAirspaces: Boolean = false,
    val isLoadingPGSpots: Boolean = false,

    // Cache state
    val currentCountryCode: String? = null,
    val airspaceCountries: Set<String> = emptySet(),
    val pgSpotCountries: Set<String> = emptySet(),

    // Configuration
    val mapStyle: String = "terrain",

    // UI state
    val compassVisible: Boolean = true,

    // Settings state - user preferences and configuration
    val settingsState: SettingsState = SettingsState(),

    // Route state - cached routes for display
    val routes: List<Route> = emptyList(),

    // Route editing state - for interactive waypoint editing
    val selectedWaypoint: WaypointSelection? = null,

    // Selected route for viewing/editing
    val selectedRouteId: String? = null,

    // Handedness-aware UI state - optimizes control placement for user preference
    val userPreferences: UserPreferencesState = UserPreferencesState(),

    // Smart Suggestion State - for nearby PG spot detection
    val smartSuggestionState: SmartSuggestionState = SmartSuggestionState(),

    // Mezulla peer state — known peers, active SOS alerts, link status.
    // Defaults to PeerState.empty() so the app works identically when
    // no board has ever been paired.
    val peerState: PeerState = PeerState.empty(),

    // Which view mode is active for peer markers on the map.
    val mezullaViewMode: MezullaViewMode = MezullaViewMode.SAFETY,

    // SOS alerts the pilot has dismissed (by sender node number).
    // Separate from PeerState.activeAlerts.acknowledgedAt because
    // "acknowledged" is a protocol concept (I saw it) while "dismissed"
    // is a UI concept (I closed the banner).
    val dismissedSosAlerts: Set<Long> = emptySet(),

    // M3: PG spot GeoJSON for MapLibre SymbolLayer rendering.
    // Produced by OverlayPrioritizer -> overlayFeaturesToGeoJson().
    // null = not yet loaded (layer hidden). Empty collection = loaded
    // but no spots nearby.
    val pgSpotGeoJson: FeatureCollection<Geometry, JsonObject>? = null,

    // Thermal-hotspot GeoJSON (kk7.ch) for the CircleLayer. null = not loaded.
    val thermalHotspotGeoJson: FeatureCollection<Geometry, JsonObject>? = null,

    // Flight deck — live data from an external vario (XC Tracer over BLE).
    val flightDeck: FlightDeckState = FlightDeckState(),
)

/** Which sensor is currently the authority for own-position (battery/quality ladder). */
enum class PositionSource { PHONE, XC_TRACER }

/**
 * Live flight-deck state fed by the external vario. When the XC Tracer is delivering
 * positioned fixes it becomes the position authority and the phone GPS is powered down
 * (better data + battery offload); on disconnect we fall back to the phone.
 */
data class FlightDeckState(
    val varioRequested: Boolean = false,   // pilot toggled "Connect vario"
    val varioConnected: Boolean = false,
    val varioScanning: Boolean = false,
    val climbMs: Double? = null,           // fused vario (m/s, + up)
    val avgClimbMs: Double? = null,        // thermal-averaged climb (the centering needle)
    val altitudeM: Double? = null,         // GPS altitude from the vario (m)
    val takeoffDatumM: Double? = null,     // first-fix altitude → height-above-takeoff
    val pressureHpa: Double? = null,
    val groundSpeedMs: Double? = null,
    val courseDeg: Double? = null,
    val batteryPct: Int? = null,
    val windFromDeg: Double? = null,       // live circling-wind estimate
    val windSpeedMs: Double? = null,
    val positionSource: PositionSource = PositionSource.PHONE,
    val lastFixMs: Long = 0L,
    /** Non-null while a bundled IGC flight is being replayed into the deck (bench demo). */
    val replayFlightId: String? = null,
)

/**
 * GPS acquisition and status tracking for aviation safety
 */
enum class GpsStatus(
    val description: String,
    val userMessage: String,
    val isOperational: Boolean
) {
    INITIAL("App started, GPS status unknown", "Initializing location services...", false),
    ACQUIRING("Requesting GPS permission and acquiring fix", "Acquiring GPS location...", false),
    SEARCHING("GPS searching for satellite fix", "Searching for GPS satellites...", false),
    ACTIVE("GPS fix acquired and updating", "GPS location active", true),
    LOST("GPS fix lost or signal weak", "GPS signal lost - aviation features limited", false),
    DISABLED("GPS disabled or permissions denied", "GPS access required for aviation features", false);

    fun isSafeForAviation(): Boolean = this == ACTIVE && isOperational
    fun requiresUserAttention(): Boolean = this == LOST || this == DISABLED
}

/**
 * Waypoint selection state for interactive editing
 */
data class WaypointSelection(
    val routeId: String,
    val waypointId: String,
    val isDragging: Boolean = false,
    val originalLat: Double? = null,
    val originalLon: Double? = null
)

/**
 * Redux state for overlay management
 */
enum class OverlayType { AIRSPACE, PG_SPOTS, ROUTES, MEZULLA, THERMAL_HOTSPOTS }

/**
 * View mode for Mezulla peer markers. Each mode shows different
 * information on the second line below the callsign.
 *
 * SAFETY (default): altitude and staleness ("1820m . 12s ago")
 * CLIMB: climb rate and relative altitude ("+1.2 m/s . +340m")
 * TACTICAL: distance, bearing, and speed ("2.4 km NW . 38 km/h")
 */
enum class MezullaViewMode {
    SAFETY,
    CLIMB,
    TACTICAL;

    fun next(): MezullaViewMode = when (this) {
        SAFETY -> CLIMB
        CLIMB -> TACTICAL
        TACTICAL -> SAFETY
    }
}

data class OverlayConfig(
    val enabled: Boolean = false,
    val opacity: Float = OverlayConstants.DEFAULT_OVERLAY_OPACITY,
    val showLabels: Boolean = true,
    val filterRadiusMiles: Double = OverlayConstants.DEFAULT_FILTER_RADIUS_MILES
)

data class OverlayState(
    val airspaces: OverlayConfig = OverlayConfig(enabled = true),
    val pgSpots: OverlayConfig = OverlayConfig(enabled = true),
    val routes: OverlayConfig = OverlayConfig(enabled = true),
    // Thermal hotspots (kk7.ch) — off by default; fetched on demand when enabled.
    val thermalHotspots: OverlayConfig = OverlayConfig(enabled = false)
)

/**
 * State for the currently displayed weather details dialog
 */
data class WeatherDialogState(
    val pgSpotId: String,
    val spotName: String,
    val forecast: com.ternparagliding.utils.io.WeatherForecast?,
    // Launch geometry (elevation + orientation) for a site-aware Flyability read.
    val siteContext: com.ternparagliding.weather.SiteContext? = null,
)

/**
 * Weather state for dynamic PG spot weather overlays
 */
data class WeatherState(
    val spotWeathers: Map<String, com.ternparagliding.utils.io.WeatherForecast> = emptyMap(),
    val waypointWeathers: Map<String, com.ternparagliding.utils.io.WeatherForecast> = emptyMap(),
    val waypointEtas: Map<String, Long> = emptyMap(),
    val fetchingSpots: Set<String> = emptySet(),
    val errors: Map<String, Throwable> = emptyMap(),
    val hasStormRisk: Boolean = false,
    val showWeatherGauges: Boolean = true,
    val showWeatherDetails: Boolean = true,
    val showingWeatherDialog: WeatherDialogState? = null,
    val weatherAPIOnline: Boolean = true,
    val cacheSize: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val lastCacheCleanup: Long = 0L,
    val lastAPIStatusCheck: Long = 0L
)

/**
 * Settings state for user preferences and configuration
 */
data class SettingsState(
    val temperatureUnit: String = "°F",
    val distanceUnit: String = "km",
    val speedUnit: String = "kn",
    val altitudeUnit: String = "ft"
)

/**
 * Handedness preference for adaptive UI layout
 */
enum class Handedness { LEFT_HANDED, RIGHT_HANDED, AMBIDEXTROUS }

enum class HandednessSource { USER_SELECTED, SYSTEM_DETECTED, SMART_DEFAULT }

/**
 * User preferences for adaptive UI
 */
data class UserPreferencesState(
    val handedness: Handedness = Handedness.RIGHT_HANDED,
    val handednessSource: HandednessSource = HandednessSource.SMART_DEFAULT,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * State for smart waypoint suggestions (nearby PG spots)
 */
data class SmartSuggestionState(
    val nearbyPGSpot: com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature? = null,
    val pendingWaypointCreation: GeoPoint? = null
)
