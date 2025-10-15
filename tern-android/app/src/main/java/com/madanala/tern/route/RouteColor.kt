package com.madanala.tern.route

import androidx.annotation.ColorInt
import com.madanala.tern.ui.theme.Cyan500

/**
 * Route styling options for waypoint routes and polylines
 * Provides aviation-appropriate colors that are visible on map backgrounds
 */
enum class RouteColor(
    @ColorInt val polylineColor: Int,
    @ColorInt val polylineWidth: Float = 6f,
    val description: String
) {
    /**
     * Primary cyan color matching the app theme
     * Best for main routes and active flight plans
     */
    CYAN(
        polylineColor = 0xFF00BCD4.toInt(), // Cyan500 from theme
        description = "Primary cyan theme color"
    ),

    /**
     * Aviation blue - traditional flight path color
     * Good visibility on most map backgrounds
     */
    AVIATION_BLUE(
        polylineColor = 0xFF0078D4.toInt(),
        description = "Traditional aviation blue"
    ),

    /**
     * High visibility orange for important routes
     * Best for emergency routes or critical waypoints
     */
    SAFETY_ORANGE(
        polylineColor = 0xFFFF5722.toInt(),
        description = "High visibility safety orange"
    ),

    /**
     * Subtle gray for background or alternative routes
     * Less distracting for secondary route information
     */
    SUBTLE_GRAY(
        polylineColor = 0xFF607D8B.toInt(),
        description = "Subtle gray for secondary routes"
    ),

    /**
     * Green for approved or completed routes
     * Good for showing completed segments or approved flight plans
     */
    SUCCESS_GREEN(
        polylineColor = 0xFF4CAF50.toInt(),
        description = "Green for completed or approved routes"
    ),

    /**
     * Purple for planned or draft routes
     * Distinctive color for routes still being planned
     */
    PLANNING_PURPLE(
        polylineColor = 0xFF9C27B0.toInt(),
        description = "Purple for planned or draft routes"
    ),

    /**
     * Red for restricted or danger areas
     * Critical color for routes requiring special attention
     */
    WARNING_RED(
        polylineColor = 0xFFF44336.toInt(),
        description = "Red for restricted or dangerous routes"
    ),

    /**
     * White with black outline for maximum contrast
     * Best for routes on dark or complex backgrounds
     */
    HIGH_CONTRAST_WHITE(
        polylineColor = 0xFFFFFFFF.toInt(),
        polylineWidth = 8f, // Slightly thicker for visibility
        description = "High contrast white with outline"
    );

    /**
     * Get the appropriate color for waypoint markers based on route color
     * Provides consistent styling between polylines and waypoint markers
     */
    @ColorInt
    fun getWaypointMarkerColor(): Int {
        return when (this) {
            CYAN -> 0xFF00BCD4.toInt() // Cyan500
            AVIATION_BLUE -> 0xFF0078D4.toInt()
            SAFETY_ORANGE -> 0xFFFF5722.toInt()
            SUBTLE_GRAY -> 0xFF455A64.toInt() // Darker gray for markers
            SUCCESS_GREEN -> 0xFF4CAF50.toInt()
            PLANNING_PURPLE -> 0xFF9C27B0.toInt()
            WARNING_RED -> 0xFFF44336.toInt()
            HIGH_CONTRAST_WHITE -> 0xFFFFFFFF.toInt()
        }
    }

    /**
     * Get the text color for waypoint labels based on the route color
     * Ensures good contrast for readability
     */
    @ColorInt
    fun getWaypointTextColor(): Int {
        return when (this) {
            HIGH_CONTRAST_WHITE -> 0xFF000000.toInt() // Black text on white
            SUBTLE_GRAY -> 0xFFFFFFFF.toInt() // White text on gray
            else -> 0xFFFFFFFF.toInt() // White text for most colors
        }
    }

    companion object {
        /**
         * Default route color for new routes
         */
        val DEFAULT = CYAN

        /**
         * Get a route color by name (case-insensitive)
         * Returns DEFAULT if name not found
         */
        fun fromName(name: String?): RouteColor {
            if (name.isNullOrBlank()) return DEFAULT

            return try {
                valueOf(name.uppercase())
            } catch (e: IllegalArgumentException) {
                // Try to match by description or partial name
                values().find { it.description.contains(name, ignoreCase = true) }
                    ?: DEFAULT
            }
        }

        /**
         * Get all available route colors for UI selection
         */
        fun getDisplayOptions(): List<RouteColor> = values().toList()
    }
}