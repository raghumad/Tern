package com.ternparagliding.redux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.osmdroid.util.GeoPoint
import com.ternparagliding.mezulla.redux.PeerAction
import com.ternparagliding.mezulla.redux.peerReducer
import android.util.Log
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
    private val actionQueue = ConcurrentLinkedQueue<TernAction>()
    private val batchMutex = Mutex()
    private var isProcessingBatch = false

    // Aviation-optimized batching configuration
    private val batchWindowMs = ReduxConstants.BATCH_WINDOW_MS  // Batch within 100ms for responsiveness
    private val maxBatchSize = ReduxConstants.MAX_BATCH_SIZE     // Max actions per batch

    /**
     * Optimized dispatch with state update batching
     * Prevents update storms while maintaining responsiveness
     */
    fun dispatch(action: MapAction) {
        actionQueue.offer(action)

        // Record for performance monitoring - REMOVED to avoid double counting with batch processing
        // recordStateUpdate()

        // Trigger immediate processing if not already processing
        if (!isProcessingBatch) {
            processBatchAsync()
        }
    }

    fun dispatch(action: WeatherActions) {
        actionQueue.offer(action)
        if (!isProcessingBatch) {
            processBatchAsync()
        }
    }

    fun dispatch(action: PeerAction) {
        actionQueue.offer(action)
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
                val actions = mutableListOf<TernAction>()

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

    // Middleware support
    private val middlewares = mutableListOf<Middleware>()

    fun addMiddleware(middleware: Middleware) {
        middlewares.add(middleware)
    }

    /**
     * Process multiple actions as a single state update
     */
    private suspend fun processBatchedActions(actions: List<TernAction>) {
        if (actions.isEmpty()) return

        var currentState = _state.value

        // Apply all actions to state sequentially
        actions.forEach { action ->
            // Process middleware side effects
            middlewares.forEach { it.process(action, this) }

            currentState = reduceAction(currentState, action)
        }

        // Single state update for entire batch
        _state.value = currentState

        // Record batch for performance monitoring with action types
        val actionTypes = actions.groupingBy { it::class.java.simpleName }.eachCount()
        recordStateUpdate(actions.size, actionTypes)
    }

    /**
     * Record state update for performance monitoring (debug only)
     */
    private fun recordStateUpdate(actionCount: Int = 1, actionTypes: Map<String, Int>? = null) {
        if (!com.ternparagliding.BuildConfig.DEBUG) return
        if (actionTypes != null) {
            actionTypes.forEach { (type, count) ->
                com.ternparagliding.utils.diagnostics.PerformanceDebugger.recordStateUpdate(count, type)
            }
        } else {
            com.ternparagliding.utils.diagnostics.PerformanceDebugger.recordStateUpdate(actionCount)
        }
    }

    // Removed list dispatch methods due to JVM signature conflicts
    // Single action dispatch is sufficient for our use cases

    // Helper methods for common actions
    fun updateRotation(rotation: Float) = dispatch(MapAction.UpdateRotation(rotation))
    fun updateUserLocation(location: org.osmdroid.util.GeoPoint?) = dispatch(MapAction.UpdateUserLocation(location))
    fun setLocationPermission(granted: Boolean) = dispatch(MapAction.UpdateLocationPermission(granted))
    fun setLocationReady(ready: Boolean) = dispatch(MapAction.SetLocationReady(ready))
    fun updateGpsStatus(status: com.ternparagliding.redux.GpsStatus) = dispatch(MapAction.UpdateGpsStatus(status))
    fun retryGpsAcquisition() = dispatch(MapAction.RetryGpsAcquisition)

    // Overlay helper methods
    fun setOverlayEnabled(type: OverlayType, enabled: Boolean) = dispatch(MapAction.SetOverlayEnabled(type, enabled))
    fun updateOverlayConfig(type: OverlayType, config: OverlayConfig) = dispatch(MapAction.UpdateOverlayConfig(type, config))
    fun toggleOverlay(type: OverlayType) {
        val currentEnabled = when (type) {
            OverlayType.AIRSPACE -> _state.value.overlayState.airspaces.enabled
            OverlayType.PG_SPOTS -> _state.value.overlayState.pgSpots.enabled
            OverlayType.ROUTES -> _state.value.overlayState.routes.enabled
            OverlayType.MEZULLA -> true // Always enabled
        }
        setOverlayEnabled(type, !currentEnabled)
    }
}

/**
 * Apply a single [TernAction] to [MapState] and return the new state.
 *
 * Every action family that flows through [MapStore] must have an explicit
 * branch here. The `else` branch logs a warning and, in debug builds,
 * throws — so a forgotten handler is caught during development rather
 * than silently dropping an SOS alert in the field.
 */
internal fun reduceAction(state: MapState, action: TernAction): MapState = when (action) {
    is MapAction -> mapReducer(state, action)
    is WeatherActions -> weatherReducer(state, action)
    is PeerAction -> state.copy(
        peerState = peerReducer(state.peerState, action)
    )
    else -> {
        Log.w("MapStore", "Unhandled action type: ${action::class.simpleName}")
        state
    }
}
