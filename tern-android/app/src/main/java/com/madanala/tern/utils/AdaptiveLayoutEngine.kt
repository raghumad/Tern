@file:Suppress("DEPRECATION")
package com.madanala.tern.utils

import android.content.Context
import android.graphics.Point
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.madanala.tern.model.FlightMode
import com.madanala.tern.redux.Handedness
import com.madanala.tern.redux.ScreenZone
import com.madanala.tern.redux.AdaptiveLayoutConfig

/**
 * Adaptive layout engine for handedness-aware UI placement
 * Calculates optimal control positioning based on user preferences and flight mode
 */
class AdaptiveLayoutEngine(private val context: Context) {

    private val TAG = "AdaptiveLayoutEngine"

    // Screen dimensions for layout calculations
    private val screenSize by lazy { getScreenDimensions() }

    /**
     * Calculate optimal layout configuration based on handedness and flight mode
     */
    fun calculateOptimalLayout(
        handedness: Handedness,
        flightMode: FlightMode,
        screenWidth: Int = screenSize.x,
        screenHeight: Int = screenSize.y
    ): AdaptiveLayoutConfig {

        Log.d(TAG, "Calculating layout for handedness: $handedness, flight mode: $flightMode")

        return AdaptiveLayoutConfig(
            criticalZone = calculateCriticalZone(handedness, flightMode, screenWidth, screenHeight),
            importantZone = calculateImportantZone(handedness, flightMode, screenWidth, screenHeight),
            secondaryZone = calculateSecondaryZone(handedness, flightMode, screenWidth, screenHeight),
            tertiaryZone = calculateTertiaryZone(handedness, flightMode, screenWidth, screenHeight),
            layoutVersion = 1,
            lastCalculated = System.currentTimeMillis(),
            requiresRecalculation = false
        )
    }

    /**
     * Calculate optimal zone for critical flight controls
     * These need to be thumb-reachable during flight
     */
    private fun calculateCriticalZone(
        handedness: Handedness,
        flightMode: FlightMode,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenZone {

        // Thumb-reachable areas (bottom ~25% of screen)
        val thumbZoneHeight = (screenHeight * 0.25).toInt()
        val bottomArea = screenHeight - thumbZoneHeight

        return when (handedness) {
            Handedness.RIGHT_HANDED -> {
                when (flightMode) {
                    FlightMode.LAUNCH -> ScreenZone.BOTTOM_RIGHT
                    FlightMode.THERMAL -> ScreenZone.BOTTOM_RIGHT
                    FlightMode.RIDGE -> ScreenZone.BOTTOM_CENTER
                    FlightMode.LANDING -> ScreenZone.BOTTOM_CENTER
                    FlightMode.COMPETITION -> ScreenZone.BOTTOM_RIGHT
                    FlightMode.GROUND -> ScreenZone.BOTTOM_RIGHT
                    FlightMode.FLIGHT -> ScreenZone.BOTTOM_RIGHT
                }
            }
            Handedness.LEFT_HANDED -> {
                when (flightMode) {
                    FlightMode.LAUNCH -> ScreenZone.BOTTOM_LEFT
                    FlightMode.THERMAL -> ScreenZone.BOTTOM_LEFT
                    FlightMode.RIDGE -> ScreenZone.BOTTOM_CENTER
                    FlightMode.LANDING -> ScreenZone.BOTTOM_CENTER
                    FlightMode.COMPETITION -> ScreenZone.BOTTOM_LEFT
                    FlightMode.GROUND -> ScreenZone.BOTTOM_LEFT
                    FlightMode.FLIGHT -> ScreenZone.BOTTOM_LEFT
                }
            }
            Handedness.AMBIDEXTROUS -> {
                // Center-bottom for ambidextrous users
                ScreenZone.BOTTOM_CENTER
            }
        }
    }

    /**
     * Calculate optimal zone for important controls
     * These should be index-finger reachable
     */
    private fun calculateImportantZone(
        handedness: Handedness,
        flightMode: FlightMode,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenZone {

        // Index-reachable areas (middle ~50% of screen)
        val middleZoneTop = (screenHeight * 0.25).toInt()
        val middleZoneBottom = (screenHeight * 0.75).toInt()

        return when (handedness) {
            Handedness.RIGHT_HANDED -> {
                when (flightMode) {
                    FlightMode.LAUNCH -> ScreenZone.MIDDLE_RIGHT
                    FlightMode.THERMAL -> ScreenZone.MIDDLE_CENTER
                    FlightMode.RIDGE -> ScreenZone.MIDDLE_RIGHT
                    FlightMode.LANDING -> ScreenZone.MIDDLE_CENTER
                    FlightMode.COMPETITION -> ScreenZone.MIDDLE_RIGHT
                    FlightMode.GROUND -> ScreenZone.MIDDLE_RIGHT
                    FlightMode.FLIGHT -> ScreenZone.MIDDLE_RIGHT
                }
            }
            Handedness.LEFT_HANDED -> {
                when (flightMode) {
                    FlightMode.LAUNCH -> ScreenZone.MIDDLE_LEFT
                    FlightMode.THERMAL -> ScreenZone.MIDDLE_CENTER
                    FlightMode.RIDGE -> ScreenZone.MIDDLE_LEFT
                    FlightMode.LANDING -> ScreenZone.MIDDLE_CENTER
                    FlightMode.COMPETITION -> ScreenZone.MIDDLE_LEFT
                    FlightMode.GROUND -> ScreenZone.MIDDLE_LEFT
                    FlightMode.FLIGHT -> ScreenZone.MIDDLE_LEFT
                }
            }
            Handedness.AMBIDEXTROUS -> {
                ScreenZone.MIDDLE_CENTER
            }
        }
    }

    /**
     * Calculate optimal zone for secondary/informational controls
     * Visual information that doesn't require frequent interaction
     */
    private fun calculateSecondaryZone(
        handedness: Handedness,
        flightMode: FlightMode,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenZone {

        // Top area for visual information (less interactive)
        return when (handedness) {
            Handedness.RIGHT_HANDED -> ScreenZone.TOP_CENTER
            Handedness.LEFT_HANDED -> ScreenZone.TOP_CENTER
            Handedness.AMBIDEXTROUS -> ScreenZone.TOP_CENTER
        }
    }

    /**
     * Calculate optimal zone for tertiary controls (settings, menu)
     * Background functionality, hidden during flight
     */
    private fun calculateTertiaryZone(
        handedness: Handedness,
        flightMode: FlightMode,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenZone {

        return when (handedness) {
            Handedness.RIGHT_HANDED -> ScreenZone.TOP_RIGHT
            Handedness.LEFT_HANDED -> ScreenZone.TOP_LEFT
            Handedness.AMBIDEXTROUS -> ScreenZone.TOP_LEFT
        }
    }

    /**
     * Get screen dimensions for layout calculations
     */
    private fun getScreenDimensions(): Point {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            // Use Context.display where available (API 30+) or fallback to defaultDisplay for older SDKs
            val display = try {
                context.display
            } catch (_: NoSuchMethodError) {
                null
            }

            if (display != null) {
                display.getRealMetrics(displayMetrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
            }

            Point(displayMetrics.widthPixels, displayMetrics.heightPixels)
        } catch (e: Exception) {
            Log.w(TAG, "Error getting screen size", e)
            Point(1080, 1920) // Default full HD size
        }
    }

    /**
     * Calculate specific control positions within zones
     */
    fun calculateControlPosition(
        controlPriority: ControlPriority,
        zone: ScreenZone,
        screenWidth: Int = screenSize.x,
        screenHeight: Int = screenSize.y
    ): ControlPosition {

        val zoneRect = getZoneRectangle(zone, screenWidth, screenHeight)

        return when (controlPriority) {
            ControlPriority.CRITICAL -> {
                // Center of the zone for critical controls
                ControlPosition(
                    x = zoneRect.centerX(),
                    y = zoneRect.centerY(),
                    width = 120,  // Larger touch targets for critical controls
                    height = 120
                )
            }
            ControlPriority.IMPORTANT -> {
                // Center of the zone for important controls
                ControlPosition(
                    x = zoneRect.centerX(),
                    y = zoneRect.centerY(),
                    width = 80,
                    height = 80
                )
            }
            ControlPriority.SECONDARY -> {
                // Center of the zone for secondary controls
                ControlPosition(
                    x = zoneRect.centerX(),
                    y = zoneRect.centerY(),
                    width = 60,
                    height = 60
                )
            }
            ControlPriority.TERTIARY -> {
                // Corner of the zone for tertiary controls
                ControlPosition(
                    x = zoneRect.left + 20,
                    y = zoneRect.top + 20,
                    width = 50,
                    height = 50
                )
            }
        }
    }

    /**
     * Get rectangle coordinates for a screen zone
     */
    private fun getZoneRectangle(zone: ScreenZone, screenWidth: Int, screenHeight: Int): ZoneRectangle {

        val zoneWidth = screenWidth / 3
        val zoneHeight = screenHeight / 3

        return when (zone) {
            ScreenZone.TOP_LEFT -> ZoneRectangle(0, 0, zoneWidth, zoneHeight)
            ScreenZone.TOP_CENTER -> ZoneRectangle(zoneWidth, 0, zoneWidth * 2, zoneHeight)
            ScreenZone.TOP_RIGHT -> ZoneRectangle(zoneWidth * 2, 0, screenWidth, zoneHeight)

            ScreenZone.MIDDLE_LEFT -> ZoneRectangle(0, zoneHeight, zoneWidth, zoneHeight * 2)
            ScreenZone.MIDDLE_CENTER -> ZoneRectangle(zoneWidth, zoneHeight, zoneWidth * 2, zoneHeight * 2)
            ScreenZone.MIDDLE_RIGHT -> ZoneRectangle(zoneWidth * 2, zoneHeight, screenWidth, zoneHeight * 2)

            ScreenZone.BOTTOM_LEFT -> ZoneRectangle(0, zoneHeight * 2, zoneWidth, screenHeight)
            ScreenZone.BOTTOM_CENTER -> ZoneRectangle(zoneWidth, zoneHeight * 2, zoneWidth * 2, screenHeight)
            ScreenZone.BOTTOM_RIGHT -> ZoneRectangle(zoneWidth * 2, zoneHeight * 2, screenWidth, screenHeight)

            ScreenZone.CENTER -> ZoneRectangle(zoneWidth, zoneHeight, zoneWidth * 2, zoneHeight * 2)
            ScreenZone.FULL_WIDTH_TOP -> ZoneRectangle(0, 0, screenWidth, zoneHeight)
            ScreenZone.FULL_WIDTH_BOTTOM -> ZoneRectangle(0, zoneHeight * 2, screenWidth, screenHeight)

            ScreenZone.THUMB_ZONE -> ZoneRectangle(zoneWidth, zoneHeight * 2, zoneWidth * 2, screenHeight)
            ScreenZone.INDEX_ZONE -> ZoneRectangle(zoneWidth, zoneHeight, zoneWidth * 2, zoneHeight * 2)
            ScreenZone.VISUAL_ZONE -> ZoneRectangle(zoneWidth, 0, zoneWidth * 2, zoneHeight)
        }
    }
}

/**
 * Control priority levels for UI placement optimization
 */
enum class ControlPriority {
    CRITICAL,    // Emergency, primary flight controls
    IMPORTANT,   // Map, weather, navigation
    SECONDARY,   // Compass, status, visual info
    TERTIARY     // Settings, menu, background
}

/**
 * Specific position and size for a control
 */
data class ControlPosition(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * Rectangle representing a screen zone
 */
data class ZoneRectangle(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2
    fun width(): Int = right - left
    fun height(): Int = bottom - top
}