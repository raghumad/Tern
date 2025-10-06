package com.madanala.tern.redux

/**
 * GPS acquisition and status tracking for aviation safety
 */
enum class GpsStatus(
    val description: String,
    val userMessage: String,
    val isOperational: Boolean
) {
    INITIAL(
        description = "App started, GPS status unknown",
        userMessage = "Initializing location services...",
        isOperational = false
    ),

    ACQUIRING(
        description = "Requesting GPS permission and acquiring fix",
        userMessage = "Acquiring GPS location...",
        isOperational = false
    ),

    ACTIVE(
        description = "GPS fix acquired and updating",
        userMessage = "GPS location active",
        isOperational = true
    ),

    LOST(
        description = "GPS fix lost or signal weak",
        userMessage = "GPS signal lost - aviation features limited",
        isOperational = false
    ),

    DISABLED(
        description = "GPS disabled or permissions denied",
        userMessage = "GPS access required for aviation features",
        isOperational = false
    );

    /**
     * Check if this status allows safe aviation operations
     */
    fun isSafeForAviation(): Boolean {
        return this == ACTIVE && isOperational
    }

    /**
     * Check if user should see warning about GPS status
     */
    fun requiresUserAttention(): Boolean {
        return this == LOST || this == DISABLED
    }
}