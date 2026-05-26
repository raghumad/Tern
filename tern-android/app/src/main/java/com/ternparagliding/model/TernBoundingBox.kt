package com.ternparagliding.model

/**
 * Platform-agnostic bounding box for spatial area representation.
 * Used for Redux-driven viewport updates (Zoom to Route).
 */
data class TernBoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0
    
    val widthLon: Double get() = maxLon - minLon
    val heightLat: Double get() = maxLat - minLat

    /**
     * Expand the bounding box by a percentage (e.g., 0.1 for 10% padding)
     */
    fun withPadding(padding: Double): TernBoundingBox {
        val latPadding = heightLat * padding
        val lonPadding = widthLon * padding
        return TernBoundingBox(
            minLat = minLat - latPadding,
            minLon = minLon - lonPadding,
            maxLat = maxLat + latPadding,
            maxLon = maxLon + lonPadding
        )
    }

    /**
     * Expand the bounding box by a fixed distance in kilometres.
     *
     * Latitude:  1 degree ~ 111.32 km (constant).
     * Longitude: 1 degree ~ 111.32 * cos(centerLat) km.
     */
    fun withPaddingKm(km: Double): TernBoundingBox {
        val latDelta = km / 111.32
        val lonDelta = km / (111.32 * Math.cos(Math.toRadians(centerLat)))
        return TernBoundingBox(
            minLat = minLat - latDelta,
            minLon = minLon - lonDelta,
            maxLat = maxLat + latDelta,
            maxLon = maxLon + lonDelta
        )
    }
}
