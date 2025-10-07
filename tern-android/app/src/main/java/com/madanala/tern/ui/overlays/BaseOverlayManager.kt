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

    protected val TAG = "OverlayManager-${overlayType.name}"
    protected var mapView: MapView? = null
    protected var isAttached = false

    // Universal animation manager for smooth transitions
    internal var animationManager: com.madanala.tern.ui.overlays.OverlayCoordinator.OverlayAnimationManager? = null
    protected var hasValidGPSFix = false

    // Adaptive overlay system for memory-based allocation
    private var adaptiveOverlaySystem: AdaptiveOverlaySystem? = null
    private var currentFlightPhase = FlightPhase.LAUNCH
    private var currentOverlayBudget: OverlayBudget? = null

    // Debouncing for map movements
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingMapMove: Runnable? = null
    private val mapMoveDebounceMs = 500L

    // Coroutine scope for async operations
    protected val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Redux state subscription
    private var stateJob: kotlinx.coroutines.Job? = null

    override fun onAttach(mapView: MapView) {
        if (isAttached) {
            Log.w(TAG, "Overlay already attached")
            return
        }

        this.mapView = mapView
        isAttached = true

        Log.d(TAG, "Attaching overlay manager")

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
        Log.d(TAG, "updateConfig: $config")
        // Implement in concrete subclasses if needed
    }

    override fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "setEnabled: $enabled")
        // Implement in concrete subclasses if needed
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

    override fun onViewportChanged(viewport: BoundingBox) {
        if (!isAttached) return
        onViewportChangedInternal(viewport)
    }

    override fun getCurrentConfig(): OverlayConfig? {
        // Return default config if no Redux store available (Phase 1)
        mapStore?.let { store ->
            val state = store.state.value
            return when (overlayType) {
                OverlayType.AIRSPACE -> state.overlayState.airspaces
                OverlayType.PG_SPOTS -> state.overlayState.pgSpots
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
     * Check if this overlay is currently enabled
     */
    fun isEnabled(): Boolean = getCurrentConfig()?.enabled ?: true

    /**
     * Update GPS fix status - called when GPS receives valid coordinates
     */
    override fun updateGPSFixStatus(hasFix: Boolean) {
        val changed = hasValidGPSFix != hasFix
        hasValidGPSFix = hasFix
        if (changed && hasFix) {
            Log.d(TAG, "GPS fix acquired - overlay operations now enabled")
        }
    }

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
    abstract override fun onReduxStateChanged(state: MapState)

    /**
     * Clear overlays specific to this manager
     */
    abstract override fun clearOverlays()

    // === MEMORY-BASED ADAPTIVE OVERLAY SYSTEM ===

    /**
     * Initialize the adaptive overlay system for memory-based allocation
     */
    private fun initializeAdaptiveOverlaySystem() {
        try {
            adaptiveOverlaySystem = AdaptiveOverlaySystem(mapView?.context ?: return)
            Log.d(TAG, "Adaptive overlay system initialized")

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
        Log.v(TAG, "Memory state changed: ${memoryState.calculatedPressure.name}")
        // Subclasses can override this for memory-specific behavior
        onMemoryStateChanged(memoryState)
    }

    /**
     * Handle budget changes from the adaptive system
     */
    private fun handleBudgetChanged(budget: OverlayBudget) {
        currentOverlayBudget = budget
        Log.d(TAG, "Overlay budget updated: ${budget.totalOverlays} total overlays")
        // Subclasses can override this for budget-specific behavior
        onOverlayBudgetChanged(budget)
    }

    /**
     * Set current flight phase for adaptive budget calculation
     */
    fun setFlightPhase(flightPhase: FlightPhase) {
        currentFlightPhase = flightPhase
        adaptiveOverlaySystem?.invalidateBudgetCache()
        Log.d(TAG, "Flight phase set to: ${flightPhase.name}")
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
     * Trigger emergency cleanup with aviation safety preservation
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

        Log.w(TAG, "🚨 EMERGENCY CLEANUP TRIGGERED: ${recommendation.reason}")

        return try {
            val zonesToClear = recommendation.recommendedZonesToClear
            var totalRemoved = 0

            // Clear zones in reverse priority order (safest first)
            zonesToClear.forEach { zone ->
                val removedInZone = clearOverlaysInZone(zone)
                totalRemoved += removedInZone
                Log.d(TAG, "Emergency cleanup: Removed $removedInZone overlays from ${zone.name} zone")
            }

            // Always preserve safety-critical zones
            val safetyCriticalPreserved = preserveSafetyCriticalOverlays()

            EmergencyCleanupResult(
                success = true,
                overlaysRemoved = totalRemoved,
                zonesCleared = zonesToClear,
                safetyCriticalPreserved = safetyCriticalPreserved,
                reason = recommendation.reason
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error during emergency cleanup", e)
            EmergencyCleanupResult(
                success = false,
                overlaysRemoved = 0,
                zonesCleared = emptyList(),
                reason = "Cleanup failed: ${e.message}"
            )
        }
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
            Log.d(TAG, "Adaptive overlay system cleaned up")
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

        Log.d(TAG, "Detaching overlay manager")

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
        coroutineScope.cancel()
    }
}
