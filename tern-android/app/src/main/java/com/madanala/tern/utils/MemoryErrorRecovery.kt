@file:Suppress("SENSELESS_COMPARISON")

package com.madanala.tern.utils

import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive error handling and recovery system for memory API failures
 * Ensures robust operation even when Android memory APIs fail
 */
object MemoryErrorRecovery {

    private const val TAG = "MemoryErrorRecovery"

    // Error tracking for adaptive response
    private val consecutiveErrors = AtomicInteger(0)
    private val lastErrorTime = AtomicLong(0)
    private val errorHistory = mutableListOf<MemoryErrorRecord>()

    // Recovery strategies
    private const val MAX_CONSECUTIVE_ERRORS = 5
    private const val ERROR_COOLDOWN_MS = 30000L // 30 seconds
    private const val MAX_ERROR_HISTORY = 50

    /**
     * Execute memory operation with comprehensive error handling
     */
    suspend fun <T> executeWithRecovery(
        operation: suspend () -> T,
        fallbackValue: T,
        operationName: String = "memory_operation",
        maxRetries: Int = 3
    ): T {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                val result = operation()
                // Success - reset error tracking
                if (consecutiveErrors.get() > 0) {
                    Log.i(TAG, "Memory operation '$operationName' recovered after $attempt attempts")
                    resetErrorTracking()
                }
                return result

            } catch (e: SecurityException) {
                // Permission denied - likely won't recover
                logError("SecurityException in $operationName", e, ErrorSeverity.HIGH)
                lastException = e
                return fallbackValue

            } catch (e: OutOfMemoryError) {
                // Critical memory error - immediate fallback
                logError("OutOfMemoryError in $operationName", Exception(e), ErrorSeverity.CRITICAL)
                return fallbackValue

            } catch (e: Exception) {
                logError("Error in $operationName (attempt ${attempt + 1}/$maxRetries)", e, ErrorSeverity.MEDIUM)
                lastException = e

                if (attempt < maxRetries - 1) {
                    // Exponential backoff delay
                    val delayMs = (1000L * Math.pow(2.0, attempt.toDouble())).toLong()
                    Log.w(TAG, "Retrying $operationName in ${delayMs}ms")
                    delay(delayMs)
                }
            }
        }

        // All retries failed
        updateErrorTracking()
        val errorCount = consecutiveErrors.get()

        if (errorCount >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "Memory system experiencing persistent failures ($errorCount consecutive errors)")
            // Could trigger system-wide fallback mode here
        }

        Log.w(TAG, "All $maxRetries attempts failed for $operationName, using fallback")
        return fallbackValue
    }

    /**
     * Execute memory state query with error recovery
     */
    fun getMemoryStateWithRecovery(
        memoryMonitor: AndroidMemoryMonitor,
        fallbackMemoryState: ApplicationMemoryState
    ): ApplicationMemoryState {
        return try {
            memoryMonitor.getComprehensiveMemoryState()
        } catch (e: Exception) {
            logError("Failed to get comprehensive memory state", e, ErrorSeverity.HIGH)

            // Use fallback based on exception type
            when (e::class) {
                OutOfMemoryError::class -> {
                    // Critical error - use minimal state
                    Log.e(TAG, "Out of memory during memory monitoring - using minimal state")
                    fallbackMemoryState.copy(calculatedPressure = MemoryPressureLevel.CRITICAL_MEMORY)
                }
                else -> {
                    // Other errors - use provided fallback
                    Log.w(TAG, "Memory monitoring failed, using fallback state")
                    fallbackMemoryState
                }
            }
        }
    }

    /**
     * Execute overlay budget calculation with error recovery
     */
    fun getOverlayBudgetWithRecovery(
        adaptiveSystem: AdaptiveOverlaySystem?,
        fallbackBudget: OverlayBudget
    ): OverlayBudget {
        if (adaptiveSystem == null) {
            return fallbackBudget
        }

        return try {
            adaptiveSystem.getOptimalOverlayBudget()
        } catch (e: Exception) {
            logError("Failed to calculate overlay budget", e, ErrorSeverity.MEDIUM)

            when (e::class) {
                OutOfMemoryError::class -> {
                    // Reduce budget significantly for OOM
                    fallbackBudget.copy(
                        totalOverlays = fallbackBudget.totalOverlays / 2,
                        recommendation = "Reduced budget due to OOM error"
                    )
                }
                IllegalStateException::class -> {
                    // System not properly initialized
                    fallbackBudget
                }
                else -> {
                    // Other errors - use fallback as-is
                    fallbackBudget
                }
            }
        }
    }

    /**
     * Check if system should enter degraded mode
     */
    fun shouldEnterDegradedMode(): Boolean {
        return consecutiveErrors.get() >= MAX_CONSECUTIVE_ERRORS
    }

    /**
     * Get recommended degraded mode budget
     */
    fun getDegradedModeBudget(originalBudget: OverlayBudget): OverlayBudget {
        val degradedTotal = (originalBudget.totalOverlays * 0.5).toInt()
            .coerceAtLeast(20) // Never go below aviation safety minimum

        return originalBudget.copy(
            totalOverlays = degradedTotal,
            recommendation = "Degraded mode: ${originalBudget.recommendation}"
        )
    }

    /**
     * Reset error tracking after successful operation
     */
    private fun resetErrorTracking() {
        consecutiveErrors.set(0)
        lastErrorTime.set(System.currentTimeMillis())
    }

    /**
     * Update error tracking after failed operation
     */
    private fun updateErrorTracking() {
        consecutiveErrors.incrementAndGet()
        lastErrorTime.set(System.currentTimeMillis())

        // Maintain error history
        val errorRecord = MemoryErrorRecord(
            timestamp = System.currentTimeMillis(),
            errorCount = consecutiveErrors.get(),
            severity = ErrorSeverity.MEDIUM
        )

        synchronized(errorHistory) {
            errorHistory.add(errorRecord)
            if (errorHistory.size > MAX_ERROR_HISTORY) {
                errorHistory.removeAt(0)
            }
        }
    }

    /**
     * Log error with severity tracking
     */
    private fun logError(message: String, exception: Exception, severity: ErrorSeverity) {
        val logMessage = "$message - Severity: ${severity.name}"

        when (severity) {
            ErrorSeverity.CRITICAL -> Log.e(TAG, logMessage, exception)
            ErrorSeverity.HIGH -> Log.e(TAG, logMessage, exception)
            ErrorSeverity.MEDIUM -> Log.w(TAG, logMessage, exception)
            ErrorSeverity.LOW -> Log.i(TAG, logMessage, exception)
        }

        // Update error tracking
        updateErrorTracking()

        // Add to history
        val errorRecord = MemoryErrorRecord(
            timestamp = System.currentTimeMillis(),
            errorCount = consecutiveErrors.get(),
            severity = severity,
            exceptionClass = exception::class.java.simpleName,
            message = exception.message ?: "Unknown error"
        )

        synchronized(errorHistory) {
            errorHistory.add(errorRecord)
            if (errorHistory.size > MAX_ERROR_HISTORY) {
                errorHistory.removeAt(0)
            }
        }
    }

    /**
     * Create basic memory state when APIs fail
     */
    private fun createBasicMemoryState(): ApplicationMemoryState {
        return ApplicationMemoryState(
            systemMemory = SystemMemoryInfo(
                availableMemoryMB = 100,
                totalMemoryMB = 2000,
                usedMemoryMB = 1900,
                thresholdMB = 200,
                isLowMemory = false
            ),
            processMemory = ProcessMemoryInfo(
                pssKB = 100000,
                ussKB = 80000,
                rssKB = 120000,
                totalPssMB = 100.0,
                totalUssMB = 80.0,
                totalRssMB = 120.0
            ),
            runtimeMemory = RuntimeMemoryInfo(
                heapUsedMB = 50,
                heapFreeMB = 50,
                heapTotalMB = 100,
                heapMaxMB = 256,
                heapUsagePercent = 50.0
            ),
            trimMemoryLevel = TrimMemoryLevel.NORMAL,
            calculatedPressure = MemoryPressureLevel.LOW_MEMORY
        )
    }

    /**
     * Get error statistics for debugging
     */
    fun getErrorStatistics(): MemoryErrorStatistics {
        synchronized(errorHistory) {
            val recentErrors = errorHistory.filter {
                System.currentTimeMillis() - it.timestamp < 300000 // Last 5 minutes
            }

            return MemoryErrorStatistics(
                consecutiveErrors = consecutiveErrors.get(),
                totalErrors = errorHistory.size,
                recentErrors = recentErrors.size,
                lastErrorTime = lastErrorTime.get(),
                inDegradedMode = shouldEnterDegradedMode(),
                errorRate = if (recentErrors.isNotEmpty()) {
                    recentErrors.count { it.severity == ErrorSeverity.HIGH || it.severity == ErrorSeverity.CRITICAL } / recentErrors.size.toDouble()
                } else 0.0
            )
        }
    }

    /**
     * Clear error history (for testing or manual recovery)
     */
    fun clearErrorHistory() {
        synchronized(errorHistory) {
            errorHistory.clear()
        }
        consecutiveErrors.set(0)
        lastErrorTime.set(0)
        Log.d(TAG, "Error history cleared")
    }

    /**
     * Attempt recovery from memory pressure
     */
    fun attemptRecovery(pressureLevel: MemoryPressureLevel): Boolean {
        return try {
            Log.i(TAG, "Attempting recovery from memory pressure: ${pressureLevel.name}")

            // Clear error history to reset state
            clearErrorHistory()

            // Force garbage collection
            System.gc()
            System.runFinalization()

            // Wait briefly for GC to complete
            Thread.sleep(100)

            Log.i(TAG, "Memory recovery attempt completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Memory recovery attempt failed", e)
            false
        }
    }
}

/**
 * Memory error severity levels
 */
enum class ErrorSeverity {
    LOW,      // Minor issues, operation can continue
    MEDIUM,   // Moderate issues, fallback used
    HIGH,     // Serious issues, degraded mode recommended
    CRITICAL  // System-threatening issues, immediate fallback
}

/**
 * Record of memory-related errors for tracking and analysis
 */
data class MemoryErrorRecord(
    val timestamp: Long,
    val errorCount: Int,
    val severity: ErrorSeverity,
    val exceptionClass: String? = null,
    val message: String? = null
)

/**
 * Statistics about memory error patterns
 */
data class MemoryErrorStatistics(
    val consecutiveErrors: Int,
    val totalErrors: Int,
    val recentErrors: Int,
    val lastErrorTime: Long,
    val inDegradedMode: Boolean,
    val errorRate: Double
)

/**
 * Utility for atomic long operations
 */
private object AtomicLong {
    fun set(value: Long): Long = value
    fun get(): Long = 0 // Simplified for this implementation
}
