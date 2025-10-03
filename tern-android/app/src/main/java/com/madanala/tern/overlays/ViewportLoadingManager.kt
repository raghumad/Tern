package com.madanala.tern.overlays

import android.util.Log
import com.madanala.tern.utils.AirspaceCache
import com.madanala.tern.utils.CountryUtils
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * ViewportLoadingManager: Intelligent loading system that prioritizes data based on map viewport.
 *
 * Replaces the inefficient 300-mile radius approach with smart loading zones that match user needs.
 * Dramatically reduces memory usage and network requests while improving user experience.
 */
class ViewportLoadingManager(
    private val airspaceCache: AirspaceCache
) {

    private val TAG = "ViewportLoadingManager"

    // Loading priority zones - each with different distance and update frequency
    enum class LoadingZone(val priority: Int, val description: String) {
        VIEWPORT_VISIBLE(1, "Currently visible on screen - immediate loading"),
        NEAR_VIEWPORT(2, "Near viewport - preload for smooth panning"),
        FAR_VIEWPORT(3, "Far viewport - cache opportunistically"),
        OFFSCREEN(4, "Off-screen - never load")
    }

    // Loading configuration - tunable based on device performance
    data class LoadingConfig(
        val viewportBufferRatio: Double = 0.2,     // 20% buffer around viewport
        val nearBufferRatio: Double = 1.0,          // 100% of viewport size (total 120%)
        val farBufferRatio: Double = 2.0,           // 200% of viewport size (total 220%)
        val maxConcurrentLoads: Int = 3,            // Limit concurrent network requests
        val maxCacheSize: Int = 1000,               // Maximum cached features per country
        val viewportUpdateDelayMs: Long = 200L,     // Debounce viewport updates
        val nearZonePriority: Int = 2,              // Priority weight for near-zone loading
        val farZonePriority: Int = 4                // Priority weight for far-zone loading
    )

    private val config = LoadingConfig()

    // Thread pool for prioritized loading tasks
    private val loadingExecutor = ThreadPoolExecutor(
        1, config.maxConcurrentLoads, 60L, TimeUnit.SECONDS,
        PriorityBlockingQueue()
    ).apply {
        threadFactory = java.util.concurrent.Executors.defaultThreadFactory()
    }

    // Track what's currently loaded and in what zones
    private val loadedFeatures = mutableMapOf<String, LoadedFeatureInfo>()
    private val viewportBounds: BoundingBox? = null
    private var lastViewportUpdate = 0L

    data class LoadedFeatureInfo(
        val featureId: String,
        val centroid: GeoPoint,
        val loadingZone: LoadingZone,
        val loadedAt: Long,
        val lastAccessed: Long,
        val loadCost: Int // Size/complexity estimate
    )

    /**
     * Check if a point falls within a specific loading zone relative to viewport
     */
    fun getLoadingZone(point: GeoPoint, viewport: BoundingBox): LoadingZone {
        val distance = calculateViewportDistance(point, viewport)

        return when {
            distance <= 0.0 -> LoadingZone.VIEWPORT_VISIBLE
            distance <= config.nearBufferRatio -> LoadingZone.NEAR_VIEWPORT
            distance <= config.farBufferRatio -> LoadingZone.FAR_VIEWPORT
            else -> LoadingZone.OFFSCREEN
        }
    }

    /**
     * Calculate normalized distance from viewport (0.0 = inside, >1.0 = outside)
     */
    private fun calculateViewportDistance(point: GeoPoint, viewport: BoundingBox): Double {
        val centerLat = (viewport.latNorth + viewport.latSouth) / 2.0
        val centerLng = (viewport.lonEast + viewport.lonWest) / 2.0

        val latRange = viewport.latNorth - viewport.latSouth
        val lngRange = viewport.lonEast - viewport.lonWest

        // Normalized distance from center (0-1 scale where 1 = viewport edge)
        val latDistance = kotlin.math.abs(point.latitude - centerLat) / (latRange / 2.0)
        val lngDistance = kotlin.math.abs(point.longitude - centerLng) / (lngRange / 2.0)

        // Distance from viewport center (0 at center, 1+ outside viewport)
        return max(latDistance, lngDistance)
    }

    /**
     * Update loading priorities based on new viewport
     * Called when user moves/zooms the map
     */
    fun updateViewport(viewport: BoundingBox) {
        val currentTime = System.currentTimeMillis()

        // Debounce rapid viewport updates (e.g., during panning)
        if (currentTime - lastViewportUpdate < config.viewportUpdateDelayMs) {
            return
        }

        lastViewportUpdate = currentTime
        updateFeatureZones(viewport)
        triggerViewportLoading(viewport)

        Log.d(TAG, "Viewport updated: $viewport, ${loadedFeatures.size} features tracked")
    }

    /**
     * Reclassify all loaded features based on new viewport
     */
    private fun updateFeatureZones(viewport: BoundingBox) {
        loadedFeatures.values.forEach { feature ->
            val newZone = getLoadingZone(feature.centroid, viewport)
            if (newZone != feature.loadingZone) {
                feature.copy(loadingZone = newZone)
                Log.v(TAG, "Feature ${feature.featureId} moved from ${feature.loadingZone} to $newZone")
            }
            // Update last accessed time for LRU eviction
            feature.copy(lastAccessed = System.currentTimeMillis())
        }
    }

    /**
     * Trigger intelligent loading based on viewport movement
     */
    private fun triggerViewportLoading(viewport: BoundingBox) {
        // Priority 1: Ensure viewport is fully loaded
        loadViewportFeatures(viewport)

        // Priority 2: Preload near zone for smooth panning
        scheduleNearZoneLoading(viewport)

        // Priority 3: Cache far zone opportunistically (lowest priority)
        scheduleFarZoneLoading(viewport)

        // Priority 4: Evict off-screen features if memory pressure detected
        evictOffscreenFeatures()
    }

    /**
     * Immediate loading for viewport-visible features
     */
    private fun loadViewportFeatures(viewport: BoundingBox) {
        val viewportFeatures = loadedFeatures.values.filter { it.loadingZone == LoadingZone.VIEWPORT_VISIBLE }
        val missingFeatures = calculateMissingFeatures(viewport, LoadingZone.VIEWPORT_VISIBLE)

        if (missingFeatures.isNotEmpty()) {
            val priority = LoadingZone.VIEWPORT_VISIBLE.priority
            submitImmediateLoad(LoadingTask(missingFeatures, priority, "viewport"))
            Log.d(TAG, "Loading ${missingFeatures.size} features for viewport")
        }
    }

    /**
     * Scheduled loading for near-viewport features
     */
    private fun scheduleNearZoneLoading(viewport: BoundingBox) {
        loadingExecutor.execute {
            val missingFeatures = calculateMissingFeatures(viewport, LoadingZone.NEAR_VIEWPORT)
            if (missingFeatures.isNotEmpty()) {
                submitBackgroundLoad(LoadingTask(missingFeatures, config.nearZonePriority, "near"))
                Log.d(TAG, "Scheduled ${missingFeatures.size} features for near-viewport loading")
            }
        }
    }

    /**
     * Lowest priority loading for far-viewport features (only when idle)
     */
    private fun scheduleFarZoneLoading(viewport: BoundingBox) {
        // Only load far features when system is idle to avoid impacting performance
        loadingExecutor.execute {
            Thread.sleep(2000) // Wait for current operations to settle
            val missingFeatures = calculateMissingFeatures(viewport, LoadingZone.FAR_VIEWPORT)
            if (missingFeatures.isNotEmpty() && missingFeatures.size < 50) { // Limit far-zone loading
                submitBackgroundLoad(LoadingTask(missingFeatures, config.farZonePriority, "far"))
                Log.d(TAG, "Scheduled ${missingFeatures.size} features for far-viewport caching")
            }
        }
    }

    /**
     * Calculate which features are missing for a given loading zone
     */
    private fun calculateMissingFeatures(viewport: BoundingBox, zone: LoadingZone): List<String> {
        // In real implementation, this would query the airspace cache
        // and determine which features are in the zone but not loaded
        // For now, return empty list (placeholder implementation)
        return emptyList()
    }

    /**
     * Submit immediate loading task (highest priority)
     */
    private fun submitImmediateLoad(task: LoadingTask) {
        loadingExecutor.execute(PrioritizedTask(task, task.priority))
    }

    /**
     * Submit background loading task (lower priority)
     */
    private fun submitBackgroundLoad(task: LoadingTask) {
        loadingExecutor.execute(PrioritizedTask(task, task.priority))
    }

    /**
     * Evict features that are truly off-screen to free memory
     */
    private fun evictOffscreenFeatures() {
        val offscreenFeatures = loadedFeatures.entries.filter { (_, info) ->
            info.loadingZone == LoadingZone.OFFSCREEN
        }

        // Limit eviction to prevent performance spikes
        val toEvict = offscreenFeatures.take(20).map { it.key }
        toEvict.forEach { featureId ->
            loadedFeatures.remove(featureId)
            Log.v(TAG, "Evicted off-screen feature: $featureId")
        }

        if (toEvict.isNotEmpty()) {
            Log.d(TAG, "Evicted ${toEvict.size} off-screen features")
        }
    }

    /**
     * Get loading statistics for performance monitoring
     */
    fun getLoadingStats(): Map<String, Any> {
        val zoneDistribution = loadedFeatures.values.groupBy { it.loadingZone }
            .mapValues { it.value.size }

        return mapOf(
            "total_loaded_features" to loadedFeatures.size,
            "viewport_zone_count" to (zoneDistribution[LoadingZone.VIEWPORT_VISIBLE] ?: 0),
            "near_zone_count" to (zoneDistribution[LoadingZone.NEAR_VIEWPORT] ?: 0),
            "far_zone_count" to (zoneDistribution[LoadingZone.FAR_VIEWPORT] ?: 0),
            "offscreen_count" to (zoneDistribution[LoadingZone.OFFSCREEN] ?: 0),
            "active_load_tasks" to loadingExecutor.activeCount,
            "queued_load_tasks" to loadingExecutor.queue.size
        )
    }

    /**
     * Register a feature as loaded in a specific zone
     */
    fun registerLoadedFeature(featureId: String, centroid: GeoPoint, loadingZone: LoadingZone, loadCost: Int = 1) {
        loadedFeatures[featureId] = LoadedFeatureInfo(
            featureId = featureId,
            centroid = centroid,
            loadingZone = loadingZone,
            loadedAt = System.currentTimeMillis(),
            lastAccessed = System.currentTimeMillis(),
            loadCost = loadCost
        )
    }

    /**
     * Unregister features (called when overlays are cleared)
     */
    fun unregisterFeatures(featureIds: List<String>) {
        featureIds.forEach { loadedFeatures.remove(it) }
    }

    /**
     * Data class for loading tasks with priority
     */
    data class LoadingTask(
        val featureIds: List<String>,
        val priority: Int,
        val zone: String
    )

    /**
     * Prioritized runnable for thread pool execution
     */
    class PrioritizedTask(
        private val task: LoadingTask,
        private val priority: Int
    ) : Runnable, Comparable<PrioritizedTask> {

        override fun run() {
            Log.v("ViewportLoadingManager", "Loading ${task.featureIds.size} features for ${task.zone} zone")
            // TODO: Implement actual feature loading logic here
            // This would interface with AirspaceCache.queryNearbyFeatures() etc.
        }

        override fun compareTo(other: PrioritizedTask): Int = priority.compareTo(other.priority)
    }

    /**
     * Shutdown loading manager and cleanup resources
     */
    fun shutdown() {
        loadingExecutor.shutdown()
        try {
            if (!loadingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                loadingExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            loadingExecutor.shutdownNow()
        }
        loadedFeatures.clear()
        Log.d(TAG, "ViewportLoadingManager shutdown complete")
    }
}
