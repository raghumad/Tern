package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Main adaptive overlay system that coordinates memory monitoring with zone-based allocation
 * This replaces hardcoded overlay limits with intelligent, device-adaptive management
 */
class AdaptiveOverlaySystem(private val context: Context) {

    companion object {
        private const val TAG = "AdaptiveOverlaySystem"
        private const val MIN_SAFE_OVERLAYS = 20  // Absolute minimum for aviation safety
        private const val MAX_OVERLAY_MULTIPLIER = 2.0  // Maximum 2x multiplier for flight phases
    }

    private val memoryMonitor = AndroidMemoryMonitor(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    // Current memory state (cached for performance)
    private var currentMemoryState: ApplicationMemoryState? = null
    private var lastBudgetCalculation = 0L
    private val BUDGET_CACHE_DURATION_MS = 5000L // Cache budgets for 5 seconds
    
    // Central zoom state for budget awareness (Priority 0)
    private var currentZoom: Double = 12.0

    /**
     * Update current zoom level centrally
     */
    fun updateZoom(zoom: Double) {
        this.currentZoom = zoom
    }

    /**
     * Get optimal overlay budget for current device and memory conditions
     */
    fun getOptimalOverlayBudget(
        flightPhase: FlightPhase = FlightPhase.LAUNCH,
        currentOverlayCount: Int = 0,
        zoom: Double = currentZoom
    ): OverlayBudget {

        val memoryState = getCurrentMemoryState()
        val baseBudget = calculateBaseBudget(memoryState)
        val flightPhaseMultiplier = getFlightPhaseMultiplier(flightPhase)

        // Apply flight phase multiplier but cap at reasonable limits
        val adjustedBudget = (baseBudget * flightPhaseMultiplier).toInt()
            .coerceIn(MIN_SAFE_OVERLAYS, memoryState.calculatedPressure.maxOverlays * 2)

        // Consider current overlay count for incremental changes
        val targetBudget = if (currentOverlayCount > 0) {
            // Smooth transition: don't change budget drastically
            val difference = adjustedBudget - currentOverlayCount
            when {
                difference > 50 -> currentOverlayCount + 50  // Increase gradually
                difference < -30 -> currentOverlayCount - 30 // Decrease gradually
                else -> adjustedBudget
            }
        } else {
            adjustedBudget
        }

        return OverlayBudget(
            totalOverlays = targetBudget,
            memoryPressure = memoryState.calculatedPressure,
            flightPhase = flightPhase,
            zoneBudgets = calculateZoneBudgets(targetBudget, memoryState, flightPhase, zoom),
            recommendation = generateBudgetRecommendation(targetBudget, memoryState, flightPhase)
        )
    }

    /**
     * Get current memory state (cached for performance)
     */
    fun getCurrentMemoryState(): ApplicationMemoryState {
        return currentMemoryState ?: memoryMonitor.getComprehensiveMemoryState().also {
            currentMemoryState = it
        }
    }

    /**
     * Calculate base budget from memory state
     */
    private fun calculateBaseBudget(memoryState: ApplicationMemoryState): Int {
        val baseBudget = memoryState.calculatedPressure.maxOverlays

        // Simple adjustment based on centralized thresholds
        val availableMB = memoryState.systemMemory.availableMemoryMB
        val memoryAdjustment = when {
            availableMB > MemoryPressureLevel.THRESHOLD_HIGH_MB -> 1.2
            availableMB > MemoryPressureLevel.THRESHOLD_MEDIUM_MB -> 1.0
            availableMB > MemoryPressureLevel.THRESHOLD_LOW_MB -> 0.8
            else -> 0.6
        }

        return (baseBudget * memoryAdjustment).toInt()
    }

    /**
     * Get flight phase multiplier for budget adjustment
     */
    private fun getFlightPhaseMultiplier(flightPhase: FlightPhase): Double {
        return when (flightPhase) {
            FlightPhase.LAUNCH -> 1.1   // Need more situational awareness for launch
            FlightPhase.THERMAL -> 1.2  // Thermal flying benefits from more overlays
            FlightPhase.LANDING -> 1.3  // Critical phase - maximum awareness needed
            FlightPhase.GLIDE -> 1.0    // Standard cross-country flying
            FlightPhase.CRUISING -> 0.9  // Long flights can work with fewer overlays
        }
    }

    /**
      * Calculate overlay budgets for each distance zone with flexible redistribution
      */
    private fun calculateZoneBudgets(
        totalBudget: Int,
        memoryState: ApplicationMemoryState,
        flightPhase: FlightPhase,
        zoom: Double
    ): Map<DistanceZone, Int> {

        // Use aviation safety budget allocation for critical memory pressure
        if (memoryState.calculatedPressure == MemoryPressureLevel.CRITICAL_MEMORY) {
            return DistanceZoneUtils.getAviationSafetyBudget(totalBudget, memoryState.calculatedPressure, zoom)
        }

        // Improved zone allocation that redistributes unused budget
        val baseAllocation = mutableMapOf<DistanceZone, Int>()

        // Start with minimum safe allocations for each zone
        val minimumAllocations = getMinimumZoneAllocations(totalBudget, flightPhase)
        var remainingBudget = totalBudget

        // Allocate minimums first
        DistanceZone.values().forEach { zone ->
            val minimum = minimumAllocations[zone] ?: 0
            baseAllocation[zone] = minimum
            remainingBudget -= minimum
        }

        // Distribute remaining budget based on priority and flight phase needs
        val priorityOrder = DistanceZone.getPriorityOrder()

        for (zone in priorityOrder) {
            if (remainingBudget <= 0) break

            // Calculate how much more this zone can benefit from additional budget
            val additionalAllocation = calculateOptimalZoneBudget(zone, remainingBudget, flightPhase, memoryState)
            val actualAllocation = kotlin.math.min(additionalAllocation, remainingBudget)

            if (actualAllocation > 0) {
                baseAllocation[zone] = baseAllocation[zone]!! + actualAllocation
                remainingBudget -= actualAllocation
            }
        }

        // If there's still budget left, distribute to zones that can use it most effectively
        if (remainingBudget > 0) {
            redistributeRemainingBudget(baseAllocation, remainingBudget, flightPhase)
        }

        Log.d(TAG, "Zone allocation: ${baseAllocation.map { "${it.key.name}:${it.value}" }.joinToString(", ")}")

        return baseAllocation
    }

    /**
     * Get minimum safe allocations for each zone to ensure aviation safety
     */
    private fun getMinimumZoneAllocations(totalBudget: Int, flightPhase: FlightPhase): Map<DistanceZone, Int> {
        // Minimum allocations based on aviation safety requirements
        val coreMinimum = kotlin.math.max(20, (totalBudget * 0.15).toInt()) // At least 20, or 15% of budget
        val nearMinimum = kotlin.math.max(15, (totalBudget * 0.10).toInt()) // At least 15, or 10% of budget

        return mapOf(
            DistanceZone.CORE to coreMinimum,
            DistanceZone.NEAR to nearMinimum,
            DistanceZone.MID to kotlin.math.max(5, (totalBudget * 0.05).toInt()),
            DistanceZone.FAR to kotlin.math.max(3, (totalBudget * 0.03).toInt()),
            DistanceZone.EXTREME to 0 // No minimum for extreme zone
        )
    }

    /**
     * Calculate optimal additional budget for a specific zone
     */
    private fun calculateOptimalZoneBudget(
        zone: DistanceZone,
        availableBudget: Int,
        flightPhase: FlightPhase,
        memoryState: ApplicationMemoryState
    ): Int {
        val flightMultiplier = zone.getFlightPhaseMultiplier(flightPhase)
        val priorityWeight = when (zone.priority) {
            1 -> 0.4 // CORE - highest priority
            2 -> 0.3 // NEAR - high priority
            3 -> 0.2 // MID - medium priority
            4 -> 0.1 // FAR - low priority
            5 -> 0.05 // EXTREME - lowest priority
            else -> 0.05
        }

        // Memory pressure affects allocation for non-critical zones
        val memoryAdjustment = when (memoryState.calculatedPressure) {
            MemoryPressureLevel.HIGH_MEMORY -> 1.0
            MemoryPressureLevel.MEDIUM_MEMORY -> 0.8
            MemoryPressureLevel.LOW_MEMORY -> 0.6
            MemoryPressureLevel.CRITICAL_MEMORY -> 0.4
        }

        // Apply flight phase and priority weighting
        val weightedAllocation = (availableBudget * flightMultiplier * priorityWeight * memoryAdjustment)

        return kotlin.math.max(0, weightedAllocation.toInt())
    }

    /**
     * Redistribute any remaining budget to zones that need it most
     */
    private fun redistributeRemainingBudget(
        allocations: MutableMap<DistanceZone, Int>,
        remainingBudget: Int,
        flightPhase: FlightPhase
    ) {
        // Priority order for redistribution (excluding EXTREME zone)
        val redistributionPriority = listOf(DistanceZone.NEAR, DistanceZone.CORE, DistanceZone.MID, DistanceZone.FAR)

        var budgetToDistribute = remainingBudget

        for (zone in redistributionPriority) {
            if (budgetToDistribute <= 0) break

            // Give zones that benefit most from the current flight phase a larger share
            val flightMultiplier = zone.getFlightPhaseMultiplier(flightPhase)
            val zoneShare = when {
                flightMultiplier > 1.0 -> (budgetToDistribute * 0.4).toInt() // Zones that benefit from this phase
                flightMultiplier > 0.7 -> (budgetToDistribute * 0.3).toInt() // Standard zones
                else -> (budgetToDistribute * 0.2).toInt() // Zones that don't benefit much
            }

            val actualShare = kotlin.math.min(zoneShare, budgetToDistribute)
            allocations[zone] = allocations[zone]!! + actualShare
            budgetToDistribute -= actualShare
        }

        // If there's still budget left, give it all to NEAR zone (most useful)
        if (budgetToDistribute > 0) {
            allocations[DistanceZone.NEAR] = allocations[DistanceZone.NEAR]!! + budgetToDistribute
        }
    }

    /**
     * Get total flight multiplier for normalization
     */
    private fun getTotalFlightMultiplier(flightPhase: FlightPhase): Double {
        return DistanceZone.values().sumOf { it.getFlightPhaseMultiplier(flightPhase) } - DistanceZone.CORE.getFlightPhaseMultiplier(flightPhase)
    }

    /**
     * Generate budget recommendation for debugging and logging
     */
    private fun generateBudgetRecommendation(
        totalBudget: Int,
        memoryState: ApplicationMemoryState,
        flightPhase: FlightPhase
    ): String {
        val memoryDesc = memoryState.calculatedPressure.description
        val flightDesc = flightPhase.description

        return "Budget: $totalBudget overlays (${memoryDesc}, ${flightDesc})"
    }

    /**
     * Check if memory conditions require emergency cleanup
     */
    fun shouldTriggerEmergencyCleanup(): Boolean {
        val memoryState = getCurrentMemoryState()

        return when (memoryState.calculatedPressure) {
            MemoryPressureLevel.CRITICAL_MEMORY -> true
            MemoryPressureLevel.LOW_MEMORY -> {
                // Trigger cleanup if memory is critically low
                memoryState.systemMemory.availableMemoryMB < 50
            }
            else -> false
        }
    }

    /**
     * Get emergency cleanup recommendation
     */
    fun getEmergencyCleanupRecommendation(): EmergencyCleanupRecommendation {
        val memoryState = getCurrentMemoryState()
        val currentBudget = getOptimalOverlayBudget()

        return when (memoryState.calculatedPressure) {
            MemoryPressureLevel.CRITICAL_MEMORY -> {
                EmergencyCleanupRecommendation(
                    shouldCleanup = true,
                    targetReduction = 0.6, // Reduce to 40% of current
                    preserveSafetyCritical = true,
                    recommendedZonesToClear = listOf(DistanceZone.EXTREME, DistanceZone.FAR, DistanceZone.MID),
                    reason = "Critical memory pressure - emergency cleanup required"
                )
            }

            MemoryPressureLevel.LOW_MEMORY -> {
                EmergencyCleanupRecommendation(
                    shouldCleanup = true,
                    targetReduction = 0.3, // Reduce to 70% of current
                    preserveSafetyCritical = true,
                    recommendedZonesToClear = listOf(DistanceZone.EXTREME, DistanceZone.FAR),
                    reason = "Low memory - moderate cleanup recommended"
                )
            }

            else -> {
                EmergencyCleanupRecommendation(
                    shouldCleanup = false,
                    targetReduction = 0.0,
                    preserveSafetyCritical = true,
                    recommendedZonesToClear = emptyList(),
                    reason = "Memory pressure normal - no cleanup needed"
                )
            }
        }
    }

    /**
     * Start continuous memory monitoring with callbacks
     */
    fun startMonitoring(
        onMemoryStateChanged: (ApplicationMemoryState) -> Unit,
        onBudgetChanged: (OverlayBudget) -> Unit,
        flightPhase: FlightPhase = FlightPhase.LAUNCH
    ) {
        Log.d(TAG, "Starting adaptive overlay system monitoring")

        memoryMonitor.startContinuousMonitoring { memoryState ->
            onMemoryStateChanged(memoryState)

            // Check if budget needs recalculation
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBudgetCalculation > BUDGET_CACHE_DURATION_MS) {
                val newBudget = getOptimalOverlayBudget(flightPhase)
                onBudgetChanged(newBudget)
                lastBudgetCalculation = currentTime
            }
        }
    }

    /**
     * Stop continuous monitoring
     */
    fun stopMonitoring() {
        Log.d(TAG, "Stopping adaptive overlay system monitoring")
        memoryMonitor.stopContinuousMonitoring()
    }

    /**
     * Force immediate budget recalculation and cache invalidation
     */
    fun invalidateBudgetCache() {
        lastBudgetCalculation = 0L
        currentMemoryState = null
        Log.d(TAG, "Budget cache invalidated")
    }

    /**
     * Get detailed system status for debugging
     */
    fun getSystemStatus(): AdaptiveSystemStatus {
        val memoryState = getCurrentMemoryState()
        val budget = getOptimalOverlayBudget()
        val detailedMemory = memoryMonitor.getDetailedMemoryInfo()

        return AdaptiveSystemStatus(
            memoryState = memoryState,
            overlayBudget = budget,
            detailedMemoryInfo = detailedMemory,
            isEmergencyCleanupRecommended = shouldTriggerEmergencyCleanup(),
            systemHealth = calculateSystemHealth(memoryState, budget)
        )
    }

    /**
     * Calculate overall system health score (0.0 = poor, 1.0 = excellent)
     */
    private fun calculateSystemHealth(
        memoryState: ApplicationMemoryState,
        budget: OverlayBudget
    ): Double {
        val memoryScore = when (memoryState.calculatedPressure) {
            MemoryPressureLevel.HIGH_MEMORY -> 1.0
            MemoryPressureLevel.MEDIUM_MEMORY -> 0.8
            MemoryPressureLevel.LOW_MEMORY -> 0.5
            MemoryPressureLevel.CRITICAL_MEMORY -> 0.2
        }

        val heapScore = when {
            memoryState.runtimeMemory.heapUsagePercent < 60 -> 1.0
            memoryState.runtimeMemory.heapUsagePercent < 75 -> 0.8
            memoryState.runtimeMemory.heapUsagePercent < 85 -> 0.6
            else -> 0.3
        }

        return (memoryScore + heapScore) / 2.0
    }

    /**
     * Cleanup when system is no longer needed
     */
    fun cleanup() {
        stopMonitoring()
        memoryMonitor.cleanup()
    }
}

/**
 * Represents the optimal overlay budget for current conditions
 */
data class OverlayBudget(
    val totalOverlays: Int,
    val memoryPressure: MemoryPressureLevel,
    val flightPhase: FlightPhase,
    val zoneBudgets: Map<DistanceZone, Int>,
    val recommendation: String
) {
    override fun toString(): String {
        return "OverlayBudget(total=$totalOverlays, " +
                "memory=${memoryPressure.name}, " +
                "flight=${flightPhase.name}, " +
                "zones=${zoneBudgets.size})"
    }
}

/**
 * Emergency cleanup recommendation for memory pressure situations
 */
data class EmergencyCleanupRecommendation(
    val shouldCleanup: Boolean,
    val targetReduction: Double, // Fraction to reduce (0.3 = reduce to 70%)
    val preserveSafetyCritical: Boolean,
    val recommendedZonesToClear: List<DistanceZone>,
    val reason: String
)

/**
 * Comprehensive system status for debugging and monitoring
 */
data class AdaptiveSystemStatus(
    val memoryState: ApplicationMemoryState,
    val overlayBudget: OverlayBudget,
    val detailedMemoryInfo: DetailedMemoryInfo,
    val isEmergencyCleanupRecommended: Boolean,
    val systemHealth: Double
) {
    override fun toString(): String {
        return "AdaptiveSystemStatus(" +
                "health=${systemHealth}, " +
                "budget=${overlayBudget.totalOverlays}, " +
                "memory=${memoryState.calculatedPressure.name}, " +
                "emergencyCleanup=$isEmergencyCleanupRecommended)"
    }
}