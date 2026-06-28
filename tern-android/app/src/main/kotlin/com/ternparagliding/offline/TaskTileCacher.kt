package com.ternparagliding.offline

import android.content.Context
import android.util.Log
import com.ternparagliding.model.Task
import com.ternparagliding.model.TernBoundingBox
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Downloads map tiles for a task's bounding area so the pilot can fly
 * with a fully offline map.
 *
 * Design principles (from Tern project memory):
 *   - Storage is free: download aggressively, never ask.
 *   - ~10 km padding around the task extent ensures the pilot has
 *     coverage even when drifting off-task in thermals.
 *   - Zoom range 8-16 gives overview context (z8) down to landing-field
 *     detail (z16).
 */
class TaskTileCacher(private val context: Context) {

    /** Progress snapshot emitted while tiles download. */
    data class CacheProgress(
        val completedResources: Long,
        val requiredResources: Long,
        val completedBytes: Long,
        val isComplete: Boolean
    ) {
        /** 0.0 .. 1.0 fraction (safe against requiredResources == 0). */
        val fraction: Float
            get() = if (requiredResources > 0)
                (completedResources.toFloat() / requiredResources).coerceIn(0f, 1f)
            else 0f
    }

    /**
     * True if a **complete** offline region already exists for [task] (matched by the
     * `task-<id>` metadata tag). Lets callers skip re-downloading a corridor whose tiles
     * are already on disk — across app restarts, where an in-memory guard can't help.
     * Returns false on any error (caller should then just (re)cache).
     */
    suspend fun isTaskCached(task: Task): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val target = "task-${task.id}".toByteArray(Charsets.UTF_8)
            OfflineManager.getInstance(context).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val exists = offlineRegions?.any { it.metadata.contentEquals(target) } == true
                        if (cont.isActive) cont.resume(exists)
                    }
                    override fun onError(error: String) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            )
        }

    /**
     * Begin downloading tiles for [task].
     *
     * Returns a cold [Flow] of [CacheProgress] that completes when
     * the download finishes (or errors). Collecting the flow triggers the
     * download; cancelling the collector cancels it.
     */
    fun cacheTask(task: Task): Flow<CacheProgress> {
        val bbox = task.extent
            ?: return kotlinx.coroutines.flow.flowOf(
                CacheProgress(0, 0, 0, true)
            )
        return cacheBoundingBox(bbox, regionName = "task-${task.id}")
    }

    /**
     * Download tiles for an arbitrary [TernBoundingBox] with ~10 km padding.
     */
    fun cacheBoundingBox(
        bbox: TernBoundingBox,
        regionName: String = "tern-region"
    ): Flow<CacheProgress> = callbackFlow {

        val padded = bbox.withPaddingKm(PADDING_KM)

        val bounds = LatLngBounds.Builder()
            .include(org.maplibre.android.geometry.LatLng(padded.minLat, padded.minLon))
            .include(org.maplibre.android.geometry.LatLng(padded.maxLat, padded.maxLon))
            .build()

        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL,
            bounds,
            MIN_ZOOM,
            MAX_ZOOM,
            context.resources.displayMetrics.density
        )

        val metadata = regionName.toByteArray(Charsets.UTF_8)

        val offlineManager = OfflineManager.getInstance(context)

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val progress = CacheProgress(
                                completedResources = status.completedResourceCount,
                                requiredResources = status.requiredResourceCount,
                                completedBytes = status.completedResourceSize,
                                isComplete = status.isComplete
                            )
                            trySend(progress)
                            if (status.isComplete) {
                                Log.i(TAG, "Tile cache complete for $regionName " +
                                        "(${status.completedResourceCount} resources, " +
                                        "${status.completedResourceSize / 1024} KB)")
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                channel.close()
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            Log.e(TAG, "Tile cache error for $regionName: " +
                                    "${error.reason} — ${error.message}")
                            // Continue downloading — transient errors are common.
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            Log.w(TAG, "Tile count limit ($limit) exceeded for $regionName")
                            channel.close()
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Failed to create offline region $regionName: $error")
                    channel.close(RuntimeException("Offline region creation failed: $error"))
                }
            }
        )

        awaitClose {
            // If the collector is cancelled, the download just stops by itself
            // when the observer is garbage-collected.  We log so it's visible
            // during development.
            Log.d(TAG, "Tile cache flow cancelled for $regionName")
        }
    }

    companion object {
        private const val TAG = "TaskTileCacher"

        /** OpenFreeMap Liberty style — same as MapViewContainer. */
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        /** Pad the task bounding box by this many km in each direction. */
        const val PADDING_KM = 10.0

        /** Overview zoom (country-level context). */
        const val MIN_ZOOM = 8.0

        /** Landing-field detail. */
        const val MAX_ZOOM = 16.0
    }
}
