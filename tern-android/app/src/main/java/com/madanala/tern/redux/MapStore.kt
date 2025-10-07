package com.madanala.tern.redux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.osmdroid.util.GeoPoint
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Redux store for map functionality - Map + Weather state management
 *
 * Now includes state update batching to prevent update storms (1304/sec → <100/sec target)
 */
class MapStore : ViewModel() {

    private val _state = MutableStateFlow(MapState())
    val state = _state.asStateFlow()

    // State update batching for performance optimization
    private val actionQueue = ConcurrentLinkedQueue<Any>()
    private val batchMutex = Mutex()
    private var isProcessingBatch = false

    // Aviation-optimized batching configuration
    private val batchWindowMs = 100L  // Batch within 100ms for responsiveness
    private val maxBatchSize = 10     // Max actions per batch

    /**
     * Optimized dispatch with state update batching
     * Prevents update storms while maintaining responsiveness
     */
    fun dispatch(action: MapAction) {
        actionQueue.offer(action)

        // Record for performance monitoring
        recordStateUpdate()

        // Trigger immediate processing if not already processing
        if (!isProcessingBatch) {
            processBatchAsync()
        }
    }

    fun dispatch(action: WeatherActions) {
        actionQueue.offer(action)

        // Record for performance monitoring (debug only)
        recordStateUpdate()

        // Trigger immediate processing if not already processing
        if (!isProcessingBatch) {
            processBatchAsync()
        }
    }

    /**
     * Process batched actions asynchronously
     */
    private fun processBatchAsync() {
        if (isProcessingBatch) return

        viewModelScope.launch {
            processBatch()
        }
    }

    /**
     * Process a batch of queued actions
     */
    private suspend fun processBatch() {
        batchMutex.withLock {
            isProcessingBatch = true

            try {
                val actions = mutableListOf<Any>()

                // Collect actions up to batch size or timeout
                var startTime = System.currentTimeMillis()
                while (actions.size < maxBatchSize) {
                    val action = actionQueue.poll() ?: break
                    actions.add(action)

                    // Check timeout (100ms batch window)
                    if (System.currentTimeMillis() - startTime > batchWindowMs) {
                        break
                    }
                }

                if (actions.isNotEmpty()) {
                    processBatchedActions(actions)
                }

            } finally {
                isProcessingBatch = false

                // If more actions queued, process next batch
                if (actionQueue.isNotEmpty()) {
                    processBatchAsync()
                }
            }
        }
    }

    /**
     * Process multiple actions as a single state update
     */
    private suspend fun processBatchedActions(actions: List<Any>) {
        if (actions.isEmpty()) return

        var currentState = _state.value

        // Apply all actions to state sequentially
        actions.forEach { action ->
            currentState = when (action) {
                is MapAction -> mapReducer(currentState, action)
                is WeatherActions -> weatherReducer(currentState, action)
                else -> currentState
            }
        }

        // Single state update for entire batch
        _state.value = currentState

        // Record batch for performance monitoring
        recordStateUpdate(actions.size)
    }

    /**
     * Record state update for performance monitoring (debug only)
     */
    private fun recordStateUpdate(actionCount: Int = 1) {
        try {
            com.madanala.tern.utils.PerformanceDebugger.recordStateUpdate(actionCount)
        } catch (e: Exception) {
            // Silently handle - performance monitoring is debug-only
        }
    }

    // Removed list dispatch methods due to JVM signature conflicts
    // Single action dispatch is sufficient for our use cases

    // Helper methods for common actions
    fun updateRotation(rotation: Float) = dispatch(MapAction.UpdateRotation(rotation))
    fun updateUserLocation(location: org.osmdroid.util.GeoPoint?) = dispatch(MapAction.UpdateUserLocation(location))
    fun setLocationPermission(granted: Boolean) = dispatch(MapAction.UpdateLocationPermission(granted))
    fun setLocationReady(ready: Boolean) = dispatch(MapAction.SetLocationReady(ready))
    fun updateGpsStatus(status: com.madanala.tern.redux.GpsStatus) = dispatch(MapAction.UpdateGpsStatus(status))
    fun retryGpsAcquisition() = dispatch(MapAction.RetryGpsAcquisition)

    // Overlay helper methods
    fun setOverlayEnabled(type: OverlayType, enabled: Boolean) = dispatch(MapAction.SetOverlayEnabled(type, enabled))
    fun updateOverlayConfig(type: OverlayType, config: OverlayConfig) = dispatch(MapAction.UpdateOverlayConfig(type, config))
    fun toggleOverlay(type: OverlayType) {
        val currentEnabled = when (type) {
            OverlayType.AIRSPACE -> _state.value.overlayState.airspaces.enabled
            OverlayType.PG_SPOTS -> _state.value.overlayState.pgSpots.enabled
        }
        setOverlayEnabled(type, !currentEnabled)
    }
}
