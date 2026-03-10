package com.madanala.tern.redux

/**
 * Aviation-grade constants for the Tern paragliding app
 * Extracted magic numbers for maintainability and consistency
 */

// Redux Store Constants
object ReduxConstants {
    /** Batch window for state updates (100ms for responsiveness) */
    const val BATCH_WINDOW_MS = 100L

    /** Maximum actions per batch to prevent update storms */
    const val MAX_BATCH_SIZE = 10
}

// Overlay Constants
object OverlayConstants {
    /** Default overlay opacity (80% visible) */
    const val DEFAULT_OVERLAY_OPACITY = 0.8f

    /** Default filter radius for spatial queries (300 miles) */
    const val DEFAULT_FILTER_RADIUS_MILES = 300.0
}

// Map View Constants
object MapConstants {
    /** Default zoom level for map initialization */
    const val DEFAULT_ZOOM_LEVEL = 8.0
}

// Route Management Constants
object RouteConstants {
    /** Maximum number of routes allowed (10-route limit) */
    const val MAX_ROUTES = 10

    /** Default FAI cylinder radius in meters (Source of Truth) */
    const val FAI_DEFAULT_RADIUS_METERS = 400.0
}

// Cache Constants
object CacheConstants {
    /** Default maximum cache size (5GB) */
    const val DEFAULT_MAX_CACHE_SIZE_MB = 5120L

    /** Bytes per megabyte conversion factor */
    const val BYTES_PER_MB = 1024 * 1024
}
