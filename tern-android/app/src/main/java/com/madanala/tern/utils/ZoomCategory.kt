package com.madanala.tern.utils

/**
 * Single source of truth for map zoom categories.
 * Used to coordinate scaling, budgeting, and feature visibility across the system.
 */
enum class ZoomCategory(
    val minZoom: Double,
    val description: String,
    val iconScale: Float,
    val iconAlpha: Float,
    val queryRadiusKm: Double,
    val showHazardIndicators: Boolean
) {
    /**
     * Continental view: Whole countries or continents visible
     */
    CONTINENTAL(0.0, "Continental view", 0.15f, 0.4f, 1000.0, false),

    /**
     * Regional view: Broad regional context (e.g., state or large province)
     */
    REGIONAL(7.0, "Regional view", 0.15f, 0.4f, 400.0, false),

    /**
     * Intermediate view: Broad local context (e.g., surrounding mountains/valleys)
     */
    INTERMEDIATE(10.0, "Intermediate view", 0.3f, 1.0f, 100.0, true),

    /**
     * Detail view: High-depth local view (e.g., launch site, landing field)
     */
    DETAIL(13.0, "Detailed local view", 0.5f, 1.0f, 20.0, true);

    companion object {
        /**
         * Determine the zoom category for a given zoom level
         */
        fun fromZoom(zoom: Double): ZoomCategory {
            return values().sortedByDescending { it.minZoom }
                .firstOrNull { zoom >= it.minZoom } ?: CONTINENTAL
        }

        // Threshold constant for regional logic (replaces hardcoded 8.0/7.0)
        const val REGIONAL_THRESHOLD = 7.0
    }
}
