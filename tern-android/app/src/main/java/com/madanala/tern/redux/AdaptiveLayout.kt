package com.madanala.tern.redux

/**
 * Screen zones for control placement optimization
 */
enum class ScreenZone {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,

    // Special zones
    CENTER, FULL_WIDTH_TOP, FULL_WIDTH_BOTTOM,

    // Adaptive zones that change based on handedness
    THUMB_ZONE, INDEX_ZONE, VISUAL_ZONE
}

/**
 * Adaptive layout configuration based on handedness and flight mode
 */
data class AdaptiveLayoutConfig(
    val criticalZone: ScreenZone = ScreenZone.BOTTOM_RIGHT,
    val importantZone: ScreenZone = ScreenZone.BOTTOM_CENTER,
    val secondaryZone: ScreenZone = ScreenZone.TOP_CENTER,
    val tertiaryZone: ScreenZone = ScreenZone.TOP_RIGHT,

    // Layout metadata
    val layoutVersion: Int = 1,
    val lastCalculated: Long = System.currentTimeMillis(),

    // Performance optimization
    val requiresRecalculation: Boolean = true
)
