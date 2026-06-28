package com.ternparagliding.redux

import org.osmdroid.util.GeoPoint

import com.ternparagliding.mezulla.redux.PeerState
import com.ternparagliding.model.Task
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
    val isTaskPanelExpanded: Boolean = true, // Strategic Auto-Minimize
    /**
     * Whether the camera follows the pilot (recenters + auto-zooms on each fix). Default on, but
     * only *acts* once airborne (see [com.ternparagliding.flight.FlightDetector]); a manual pan/zoom
     * turns it off so the pilot can study the map, and the Recenter button turns it back on.
     */
    val cameraFollow: Boolean = true,

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
    /** Names of controlled airspaces the active task crosses (waypoint-inside or leg-crossing). */
    val airspaceConflicts: List<String> = emptyList(),

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

    // Task state - cached tasks for display
    val tasks: List<Task> = emptyList(),

    // Standalone waypoint library — waypoints exist independently of tasks (comp
    // organisers issue the waypoint set first; tasks reference them later). The
    // source the pilot imports (.cup/.wpt/.gpx) and builds tasks from.
    val waypointLibrary: List<com.ternparagliding.model.LibraryWaypoint> = emptyList(),

    // Task editing state - for interactive waypoint editing
    val selectedWaypoint: WaypointSelection? = null,

    // Move-mode for a standalone library spot (Workflow A "Move on map"): when set,
    // the next map tap repositions this spot. Parallel to selectedWaypoint.isDragging,
    // which is move-mode for a *task* point.
    val movingSpotId: String? = null,

    // Add-from-map mode: while true the planning chrome (HUD + task panel) gets out
    // of the way, the map shows a centre crosshair, and an action bar drops a point
    // at the crosshair into the selected task — the industry "centre-pin placement"
    // pattern, so the pilot never has to tap an obscured target. Persists across
    // drops so several points can be placed before tapping Done.
    val addingWaypoint: Boolean = false,

    // Selected task for viewing/editing
    val selectedTaskId: String? = null,

    // Active-task navigation. activeWaypointId is the next waypoint the pilot is
    // flying to (the first not-yet-tagged point of the selected task); tagged
    // are the ones already reached (flew into the cylinder). Together they drive
    // the on-map target highlight and the off-screen direction chip — the
    // buddy-style "next waypoint shows up on screen" guidance.
    val activeWaypointId: String? = null,
    val taggedWaypointIds: Set<String> = emptySet(),

    // Handedness-aware UI state - optimizes control placement for user preference
    val userPreferences: UserPreferencesState = UserPreferencesState(),

    // Smart Suggestion State - for nearby PG spot detection
    val smartSuggestionState: SmartSuggestionState = SmartSuggestionState(),

    // Mezulla peer state — known peers, active SOS alerts, link status.
    // Defaults to PeerState.empty() so the app works identically when
    // no board has ever been paired.
    val peerState: PeerState = PeerState.empty(),

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

/**
 * Tasks with their library references resolved (Stage B2): linked task points take
 * their identity (position/code/name/alt) from the current [waypointLibrary], so
 * editing/re-importing a library waypoint flows into every task using it. Read
 * paths (map rendering, nav, ribbon) should consume this, not raw [MapState.tasks].
 */
fun MapState.resolvedTasks(): List<Task> =
    com.ternparagliding.overlay.task.TaskResolver.resolveAll(tasks, waypointLibrary)

/** The selected task, with library references resolved. */
fun MapState.resolvedSelectedTask(): Task? =
    selectedTaskId?.let { id -> resolvedTasks().find { it.id == id } }

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
    /** Recent vario BLE connection events (newest last), so the pilot can see the link's
     *  status + every drop/heal. Bounded — see [com.ternparagliding.device.ConnectionEvent]. */
    val connectionEvents: List<com.ternparagliding.device.ConnectionEvent> = emptyList(),
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
    val taskId: String,
    val waypointId: String,
    val isDragging: Boolean = false,
    val originalLat: Double? = null,
    val originalLon: Double? = null,
    // True when this selection is a freshly *dropped* point (long-press create), so the
    // UI highlights it but does NOT throw up the full editor — the pilot keeps dropping;
    // tapping a point (a non-new selection) is what opens the editor.
    val isNew: Boolean = false,
)

/**
 * Redux state for overlay management
 */
enum class OverlayType { AIRSPACE, PG_SPOTS, TASKS, MEZULLA, THERMAL_HOTSPOTS }

data class OverlayConfig(
    val enabled: Boolean = false,
    val opacity: Float = OverlayConstants.DEFAULT_OVERLAY_OPACITY,
    val showLabels: Boolean = true,
    val filterRadiusMiles: Double = OverlayConstants.DEFAULT_FILTER_RADIUS_MILES
)

data class OverlayState(
    val airspaces: OverlayConfig = OverlayConfig(enabled = true),
    val pgSpots: OverlayConfig = OverlayConfig(enabled = true),
    val tasks: OverlayConfig = OverlayConfig(enabled = true),
    // Standalone waypoint library markers — on by default (the pilot imported them).
    val waypoints: OverlayConfig = OverlayConfig(enabled = true),
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
    val altitudeUnit: String = "ft",
    /** The MAC of the pilot's chosen XC Tracer (from the "Find my vario" picker), persisted so
     *  Tern only ever connects to *their* vario and auto-reconnects to it. Null until picked. */
    val rememberedVarioMac: String? = null,
    /** The chosen vario's display name, for the Settings row. */
    val rememberedVarioName: String? = null,
    /** Deliberate pause for the remembered vario. A remembered + un-paused vario auto-connects
     *  on launch and self-heals; pausing is the only thing that stops it (besides Forget).
     *  Persisted, so the auto-connect intent survives restarts. */
    val varioPaused: Boolean = false,
    /** The pilot's current Mezulla team — *intent*, owned by the phone independent of the board.
     *  Created/joined offline; applied to the board when it connects. Null until created/joined. */
    val teamName: String? = null,
    /** The shareable `tern://team?…` link for the current team — re-shown as a QR so buddies can
     *  join, and the credential (name+key) the reconcile step writes to the board. Null when none. */
    val teamShareLink: String? = null,
    /** Where the team came from: "manual" (QR/link) today; "spedmo-club" later (Epic 03 Story 3.9).
     *  The board doesn't care which; this is just for display + future routing. */
    val teamSource: String? = null,
    /** The team link we have actually written to the board (set_team succeeded). When it equals
     *  [teamShareLink] the board is on our team ("applied"); when it differs (or is null) the team
     *  is "pending" — the reconcile step applies it on the next live link. Tracking it avoids
     *  rewriting an unchanged channel on every launch. */
    val teamAppliedLink: String? = null,
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
