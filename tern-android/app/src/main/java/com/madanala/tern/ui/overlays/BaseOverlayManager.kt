package com.madanala.tern.ui.overlays

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.AdaptiveOverlaySystem
import com.madanala.tern.utils.AdaptiveOverlayFallback
import com.madanala.tern.utils.ApplicationMemoryState
import com.madanala.tern.utils.DistanceZone
import com.madanala.tern.utils.FlightPhase
import com.madanala.tern.utils.MemoryErrorRecovery
import com.madanala.tern.utils.MemoryPressureLevel
import com.madanala.tern.utils.OverlayBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

// Emergency cleanup result for memory pressure situations
data class EmergencyCleanupResult(
    val success: Boolean,
    val overlaysRemoved: Int,
    val zonesCleared: List<com.madanala.tern.utils.DistanceZone>,
    val invisibleOverlaysRemoved: Int = 0,
    val safetyCriticalPreserved: Int = 0,
    val reason: String
)

/**
 * Base implementation for overlay managers with common functionality
 */
abstract class BaseOverlayManager(
    final override val overlayType: OverlayType,
    protected var mapStore: MapStore?
) : OverlayManager {

    companion object {
        // Minimum zoom level to show overlays (Region/County level)
        // Minimum zoom level to show overlays (Region/County level)
        // const val MIN_ZOOM_FOR_OVERLAYS = 7.0 - Deprecated
    }

    protected val TAG = "OverlayManager-${overlayType.name}"
    protected var mapView: MapView? = null
    protected var isAttached = false


    // Universal animation manager for smooth transitions
    internal var animationManager: com.madanala.tern.ui.overlays.OverlayCoordinator.OverlayAnimationManager? = null


    // Adaptive overlay system for memory-based allocation
    private var adaptiveOverlaySystem: AdaptiveOverlaySystem? = null
    private var currentFlightPhase = FlightPhase.LAUNCH
    // Focus mode for decluttering during interaction
    protected var _isFocusMode = false
    private var currentOverlayBudget: OverlayBudget? = null

    // Debouncing for map movements
    protected val mainHandler = Handler(Looper.getMainLooper())
    protected var pendingMapMove: Runnable? = null
    protected var mapMoveDebounceMs = 300L

    // Coroutine scope for async operations
    private var _coroutineScope: CoroutineScope? = null
    protected val coroutineScope: CoroutineScope
        get() = _coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { _coroutineScope = it }

    // Redux state subscription
    private var stateJob: kotlinx.coroutines.Job? = null

    /**
     * Check if the current zoom level is sufficient to show overlays
     */
    protected fun isZoomLevelSufficient(zoom: Double): Boolean {
        return true // Zoom thresholds removed in favor of zone budgeting
    }

    /**
     * Prioritize features based on distance from center and limit count
     * @param items List of items to prioritize
     * @param center Map center
     * @param limit Maximum number of items to return
     * @param locationSelector Function to extract GeoPoint from item
     */
    protected fun <T> prioritizeFeatures(
        items: List<T>,
        center: GeoPoint,
        limit: Int,
        locationSelector: (T) -> GeoPoint
    ): List<T> {
        return items.sortedBy { item ->
            locationSelector(item).distanceToAsDouble(center)
        }.take(limit)
    }

    /**
     * Sort items for addition: Center -> Outside (Ascending distance)
     * This creates a "growing from center" effect when combined with staggered animation
     */
    protected fun <T> sortForAddition(
        items: List<T>,
        center: GeoPoint,
        locationSelector: (T) -> GeoPoint
    ): List<T> {
        return items.sortedBy { item ->
            locationSelector(item).distanceToAsDouble(center)
        }
    }

    /**
     * Sort items for removal: Outside -> Center (Descending distance)
     * This creates a "shrinking to center" effect when combined with staggered animation
     */
    protected fun <T> sortForRemoval(
        items: List<T>,
        center: GeoPoint,
        locationSelector: (T) -> GeoPoint
    ): List<T> {
        return items.sortedByDescending { item ->
            locationSelector(item).distanceToAsDouble(center)
        }
    }

    /**
     * Prioritize features based on strict zone-based budgeting
     * Enforces limits per zone (CORE, NEAR, FAR) to ensure consistent density
     */
    protected fun <T> prioritizeFeaturesByZone(
        items: List<T>,
        center: GeoPoint,
        locationSelector: (T) -> GeoPoint
    ): List<T> {
        val budget = getCurrentOverlayBudget() ?: return prioritizeFeatures(items, center, 10, locationSelector) // Fallback

        // Group items by zone
        val zoneMap = com.madanala.tern.utils.DistanceZone.values().associateWith { mutableListOf<T>() }
        
        items.forEach { item ->
            val distanceKm = locationSelector(item).distanceToAsDouble(center) / 1000.0
            val zone = com.madanala.tern.utils.DistanceZone.fromDistanceKm(distanceKm)

            zoneMap[zone]?.add(item)
        }
        
        val result = mutableListOf<T>()
        
        // Iterate through zones by priority order (critical first)
        // Although budget is per-zone, processing order matters for consistency
        com.madanala.tern.utils.DistanceZone.getPriorityOrder().forEach { zone ->
            val zoneItems = zoneMap[zone] ?: return@forEach
            val zoneLimit = budget.zoneBudgets[zone] ?: 0
            
            Log.d(TAG, "Zone ${zone.name}: ${zoneItems.size} items, limit $zoneLimit")

            // Sort items in this zone by distance (closest first) and take only up to the limit
            val acceptedItems = zoneItems.sortedBy { item ->
                 locationSelector(item).distanceToAsDouble(center)
            }.take(zoneLimit)
            
            result.addAll(acceptedItems)
        }
        
        return result
    }

    override fun onAttach(mapView: MapView) {
        if (isAttached) {
            Log.w(TAG, "Overlay already attached")
            return
        }

        this.mapView = mapView
        isAttached = true


        // Initialize adaptive overlay system
        initializeAdaptiveOverlaySystem()

        // Start listening to Redux state
        startReduxSubscription()

        // Perform initial overlay setup
        onOverlayAttached()
    }

    override fun initialize(mapView: MapView) {
        onAttach(mapView)
    }


    override fun updateConfig(config: OverlayConfig) {
        // Log.d(TAG, "updateConfig: $config")
        // Implement in concrete subclasses if needed
    }

    override fun setEnabled(enabled: Boolean) {
        // Log.d(TAG, "setEnabled: $enabled")
        // Implement in concrete subclasses if needed
    }

    override fun setFocusMode(enabled: Boolean) {
        if (_isFocusMode != enabled) {
            _isFocusMode = enabled
            onFocusModeChanged(enabled)
            Log.d(TAG, "Focus mode changed: $enabled")
        }
    }

    /**
     * Called when focus mode changes. Override in subclasses to apply visual changes.
     */
    protected open fun onFocusModeChanged(enabled: Boolean) {
        // Default implementation does nothing
    }

    override fun getPerformanceStats(): Map<String, Any> {
        val adaptiveStats = mutableMapOf<String, Any>(
            "overlay_type" to overlayType.name,
            "attached" to isAttached,
            "has_pending_move" to (pendingMapMove != null)
        )

        // Add adaptive overlay system statistics
        currentOverlayBudget?.let { budget ->
            adaptiveStats["current_budget"] = budget.totalOverlays
            adaptiveStats["memory_pressure"] = budget.memoryPressure.name
            adaptiveStats["flight_phase"] = budget.flightPhase.name
            adaptiveStats["zone_budgets"] = budget.zoneBudgets
        }

        // Add error recovery statistics
        val errorStats = MemoryErrorRecovery.getErrorStatistics()
        adaptiveStats["error_stats"] = mapOf(
            "consecutive_errors" to errorStats.consecutiveErrors,
            "total_errors" to errorStats.totalErrors,
            "in_degraded_mode" to errorStats.inDegradedMode,
            "error_rate" to errorStats.errorRate
        )

        // Add adaptive system availability
        adaptiveStats["adaptive_system_available"] = (adaptiveOverlaySystem != null)
        adaptiveStats["emergency_cleanup_recommended"] = shouldTriggerEmergencyCleanup()

        return adaptiveStats
    }

    override fun getRenderedCount(): Int {
        return 0 // Default implementation, override in specifically tracked managers
    }

    /**
      * Get detailed overlay visibility statistics for debugging
      */
    fun getDetailedOverlayStats(): Map<String, Any> {
        val mapOverlayCount = mapView?.overlays?.size ?: 0

        return mapOf(
            "overlay_type" to overlayType.name,
            "budget_total" to (currentOverlayBudget?.totalOverlays ?: 0),
            "memory_pressure" to (currentOverlayBudget?.memoryPressure?.name ?: "unknown"),
            "flight_phase" to (currentOverlayBudget?.flightPhase?.name ?: "unknown"),
            "adaptive_system_available" to (adaptiveOverlaySystem != null),
            "error_stats" to MemoryErrorRecovery.getErrorStatistics(),
            "map_overlay_count" to mapOverlayCount,
            "location" to if (mapView?.mapCenter != null) {
                String.format("%.4f,%.4f", mapView!!.mapCenter.latitude, mapView!!.mapCenter.longitude)
            } else "unknown"
        )
    }

    /**
     * Force refresh of overlay visibility (for debugging visibility issues)
     */
    fun forceOverlayVisibilityRefresh() {
        mapView?.let { map ->
            val overlayCount = map.overlays.size
            // Log.d(TAG, "Force refresh: Map contains ${overlayCount} total overlays")

            // Force map invalidation to ensure overlays are drawn
            map.invalidate()

            // Post another invalidation to ensure animations complete
            map.postInvalidateDelayed(100)
        }
    }

    override fun onViewportChanged(viewport: BoundingBox) {
        if (!isAttached) return
        Log.i(TAG, "Rendering heartbeat: Viewport update processed")
        onViewportChangedInternal(viewport)
    }

    override fun getCurrentConfig(): OverlayConfig? {
        // Return default config if no Redux store available (Phase 1)
        mapStore?.let { store ->
            val state = store.state.value
            return when (overlayType) {
                OverlayType.AIRSPACE -> state.overlayState.airspaces
                OverlayType.PG_SPOTS -> state.overlayState.pgSpots
                OverlayType.ROUTES -> state.overlayState.routes
            }
        }
        // Default config when no Redux store (all enabled for stability)
        return OverlayConfig(enabled = true)
    }

    /**
      * Set the Redux store (for late initialization)
      */
    override fun setReduxStore(store: MapStore?) {
        val wasNull = mapStore == null
        mapStore = store
        // If we just got a store and we're attached, start the subscription
        if (wasNull && store != null && isAttached) {
            startReduxSubscription()
        }
        // Trigger state change with current state
        store?.state?.value?.let { onReduxStateChanged(it) }
    }

    /**
      * Set the overlay coordinator (for RouteOverlayManager integration with Hilbert ordering)
      */
    open fun setOverlayCoordinator(coordinator: OverlayCoordinator) {
        // Default implementation - RouteOverlayManager will override this
    }


    /**
     * Check if this overlay is currently enabled
     */
    fun isEnabled(): Boolean = getCurrentConfig()?.enabled ?: true



    /**
     * Start subscribing to Redux state changes
     */
    private fun startReduxSubscription() {
        mapStore?.let { store ->
            stateJob = coroutineScope.launch {
                store.state.collectLatest { state ->
                    onReduxStateChanged(state)
                }
            }
        } ?: Log.d(TAG, "No Redux store available - skipping state subscription")
    }

    /**
     * Stop subscribing to Redux state changes
     */
    private fun stopReduxSubscription() {
        stateJob?.cancel()
        stateJob = null
    }

    /**
     * Called when the overlay is first attached to perform initialization
     */
    protected abstract fun onOverlayAttached()

    /**
     * Called when the overlay is detached to perform cleanup
     */
    protected abstract fun onOverlayDetached()

    /**
     * Perform the actual map move logic after debouncing
     */
    abstract override fun performMapMove(center: GeoPoint, zoom: Double)

    /**
     * Handle viewport changes (for performance optimization)
     */
    protected abstract fun onViewportChangedInternal(viewport: BoundingBox)

    /**
     * Called when Redux state changes - override to react to state updates
     */
    override fun onReduxStateChanged(state: MapState) {
        // Automatically handle focus mode based on waypoint dragging
        val shouldFocus = state.selectedWaypoint?.isDragging == true
        setFocusMode(shouldFocus)
    }

    /**
     * Clear overlays specific to this manager
     */
    abstract override fun clearOverlays()

    fun setDebounceMs(ms: Long) {
        mapMoveDebounceMs = ms
    }

    /**
     * Debounce map movements to prevent state update storms
     */
    fun scheduleMapMove(center: GeoPoint, zoom: Double) {
        pendingMapMove?.let { mainHandler.removeCallbacks(it) }
        pendingMapMove = Runnable {
            performMapMove(center, zoom)
        }
        mainHandler.postDelayed(pendingMapMove!!, mapMoveDebounceMs)
    }

    // === MEMORY-BASED ADAPTIVE OVERLAY SYSTEM ===

    /**
     * Initialize the adaptive overlay system for memory-based allocation
     */
    private fun initializeAdaptiveOverlaySystem() {
        try {
            adaptiveOverlaySystem = AdaptiveOverlaySystem(mapView?.context ?: return)
            // Log.d(TAG, "Adaptive overlay system initialized")

            // Start monitoring with callbacks
            adaptiveOverlaySystem?.startMonitoring(
                onMemoryStateChanged = { memoryState ->
                    handleMemoryStateChanged(memoryState)
                },
                onBudgetChanged = { budget ->
                    handleBudgetChanged(budget)
                },
                flightPhase = currentFlightPhase
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize adaptive overlay system", e)
            // Continue without adaptive system (graceful degradation)
        }
    }

    /**
     * Handle memory state changes from the adaptive system
     */
    private fun handleMemoryStateChanged(memoryState: ApplicationMemoryState) {
        // Subclasses can override this for memory-specific behavior
        onMemoryStateChanged(memoryState)
    }

    /**
      * Handle budget changes from the adaptive system
      */
    private fun handleBudgetChanged(budget: OverlayBudget) {
        currentOverlayBudget = budget

        // Get current map center for geographic context
        val center = mapView?.mapCenter
        val centerStr = if (center != null) {
            String.format("@ %.4f,%.4f", center.latitude, center.longitude)
        } else {
            "@ unknown location"
        }

        Log.d(TAG, String.format(
            "Budget: %d total (Memory: %s, Flight: %s) %s",
            budget.totalOverlays,
            budget.memoryPressure.name,
            budget.flightPhase.name,
            centerStr
        ))

        // Subclasses can override this for budget-specific behavior
        onOverlayBudgetChanged(budget)
    }

    /**
     * Set current flight phase for adaptive budget calculation
     */
    fun setFlightPhase(flightPhase: FlightPhase) {
        currentFlightPhase = flightPhase
        adaptiveOverlaySystem?.invalidateBudgetCache()
        // Log.d(TAG, "Flight phase set to: ${flightPhase.name}")
    }

    /**
      * Get current overlay budget (memory-based allocation with error recovery)
      */
    fun getCurrentOverlayBudget(): OverlayBudget? {
        return try {
            val budget = MemoryErrorRecovery.getOverlayBudgetWithRecovery(
                adaptiveSystem = adaptiveOverlaySystem,
                fallbackBudget = AdaptiveOverlayFallback.getFallbackOverlayBudget(
                    overlayType.name.lowercase(),
                    currentFlightPhase
                )
            )
            AdaptiveOverlayFallback.validateOrFallback(
                budget = budget,
                overlayType = overlayType.name.lowercase(),
                flightPhase = currentFlightPhase
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting overlay budget, using fallback", e)
            AdaptiveOverlayFallback.getFallbackOverlayBudget(overlayType.name.lowercase(), currentFlightPhase)
        }
    }

    /**
      * Get budget for specific distance zone (with error recovery)
      */
    fun getZoneBudget(distanceZone: DistanceZone): Int {
        return try {
            val budget = getCurrentOverlayBudget()
            budget?.zoneBudgets?.get(distanceZone)
                ?: AdaptiveOverlayFallback.getFallbackZoneBudgets(overlayType.name.lowercase())[distanceZone]
                ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting zone budget for ${distanceZone.name}, using fallback", e)
            AdaptiveOverlayFallback.getFallbackZoneBudgets(overlayType.name.lowercase())[distanceZone] ?: 0
        }
    }

    /**
      * Get maximum overlays for current memory conditions (with error recovery)
      */
    fun getMaxOverlaysForCurrentConditions(): Int {
        return try {
            getCurrentOverlayBudget()?.totalOverlays
                ?: AdaptiveOverlayFallback.getFallbackOverlayBudget(overlayType.name.lowercase(), currentFlightPhase).totalOverlays
        } catch (e: Exception) {
            Log.e(TAG, "Error getting max overlays, using fallback", e)
            AdaptiveOverlayFallback.getFallbackOverlayBudget(overlayType.name.lowercase(), currentFlightPhase).totalOverlays
        }
    }

    /**
     * Check if emergency cleanup is recommended
     */
    fun shouldTriggerEmergencyCleanup(): Boolean {
        return adaptiveOverlaySystem?.shouldTriggerEmergencyCleanup() ?: false
    }

    /**
     * Get emergency cleanup recommendation
     */
    fun getEmergencyCleanupRecommendation(): com.madanala.tern.utils.EmergencyCleanupRecommendation? {
        return adaptiveOverlaySystem?.getEmergencyCleanupRecommendation()
    }

    /**
     * Trigger emergency cleanup with aviation safety preservation and viewport awareness
     */
    fun triggerEmergencyCleanup(): EmergencyCleanupResult {
        val recommendation = getEmergencyCleanupRecommendation()

        if (recommendation == null || !recommendation.shouldCleanup) {
            return EmergencyCleanupResult(
                success = false,
                overlaysRemoved = 0,
                zonesCleared = emptyList(),
                reason = "No cleanup needed"
            )
        }

        Log.w(TAG, "🚨 ENHANCED EMERGENCY CLEANUP: ${recommendation.reason}")

        return try {
            var totalRemoved = 0
            val zonesCleared = mutableListOf<com.madanala.tern.utils.DistanceZone>()
            var invisibleRemoved = 0

            // Phase 1: Remove invisible overlays first (most memory-efficient)
            invisibleRemoved = removeInvisibleOverlays()
            totalRemoved += invisibleRemoved
            if (invisibleRemoved > 0) {
                // Log.d(TAG, "🚀 Viewport cleanup: Removed $invisibleRemoved invisible overlays (most efficient)")
            } else {
                // Log.d(TAG, "Viewport cleanup: No invisible overlays found")
            }

            // Phase 2: Clear distance zones in reverse priority order (safest first)
            val zonesToClear = recommendation.recommendedZonesToClear
            zonesToClear.forEach { zone ->
                val removedInZone = clearOverlaysInZone(zone)
                totalRemoved += removedInZone
                if (removedInZone > 0) {
                    zonesCleared.add(zone)
                }
                // Log.d(TAG, "Zone cleanup: Removed $removedInZone overlays from ${zone.name} zone")
            }

            // Always preserve safety-critical zones
            val safetyCriticalPreserved = preserveSafetyCriticalOverlays()

            // Log cleanup efficiency summary
            val efficiencyImprovement = if (invisibleRemoved > 0) {
                " (${invisibleRemoved} invisible + ${totalRemoved - invisibleRemoved} distance-based)"
            } else {
                " (${totalRemoved} distance-based)"
            }

            Log.i(TAG, "✅ Enhanced cleanup complete: Removed $totalRemoved overlays$efficiencyImprovement, preserved $safetyCriticalPreserved safety-critical")

            EmergencyCleanupResult(
                success = true,
                overlaysRemoved = totalRemoved,
                zonesCleared = zonesCleared,
                invisibleOverlaysRemoved = invisibleRemoved,
                safetyCriticalPreserved = safetyCriticalPreserved,
                reason = "Enhanced cleanup: ${recommendation.reason}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error during enhanced emergency cleanup", e)
            EmergencyCleanupResult(
                success = false,
                overlaysRemoved = 0,
                zonesCleared = emptyList(),
                reason = "Enhanced cleanup failed: ${e.message}"
            )
        }
    }

    /**
     * Remove overlays that are not visible in the current viewport (most memory-efficient)
     */
    protected open fun removeInvisibleOverlays(): Int {
        // Default implementation - subclasses should override for viewport-specific cleanup
        return 0
    }

    /**
     * Clear overlays in a specific distance zone
     */
    protected open fun clearOverlaysInZone(zone: com.madanala.tern.utils.DistanceZone): Int {
        // Default implementation - subclasses should override for zone-specific cleanup
        return 0
    }

    /**
     * Preserve safety-critical overlays during emergency cleanup
     */
    protected open fun preserveSafetyCriticalOverlays(): Int {
        // Default implementation - subclasses should override for safety-critical preservation
        return 0
    }

    /**
     * Called when memory state changes - override in subclasses for custom behavior
     */
    protected open fun onMemoryStateChanged(memoryState: ApplicationMemoryState) {
        // Default implementation - subclasses can override
    }

    /**
     * Called when overlay budget changes - override in subclasses for custom behavior
     */
    protected open fun onOverlayBudgetChanged(budget: OverlayBudget) {
        // Default implementation - subclasses can override
    }

    /**
     * Cleanup adaptive overlay system when detaching
     */
    private fun cleanupAdaptiveOverlaySystem() {
        try {
            adaptiveOverlaySystem?.cleanup()
            adaptiveOverlaySystem = null
            currentOverlayBudget = null
            // Log.d(TAG, "Adaptive overlay system cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up adaptive overlay system", e)
        }
    }

    /**
     * Update cleanup to include adaptive system
     */
    override fun onDetach() {
        if (!isAttached) {
            Log.w(TAG, "Overlay not attached")
            return
        }

        // Log.d(TAG, "Detaching overlay manager")

        // Cleanup adaptive overlay system first
        cleanupAdaptiveOverlaySystem()

        // Cancel pending operations
        pendingMapMove?.let { mainHandler.removeCallbacks(it) }
        pendingMapMove = null

        // Stop Redux subscription
        stopReduxSubscription()

        // Perform overlay cleanup
        onOverlayDetached()

        // Clear references
        mapView = null
        isAttached = false

        // Cancel coroutine scope
        _coroutineScope?.cancel()
        _coroutineScope = null
    }
}
