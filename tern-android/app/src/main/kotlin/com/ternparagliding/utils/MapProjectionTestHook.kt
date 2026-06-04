package com.ternparagliding.utils

import androidx.compose.ui.geometry.Offset

/**
 * Test-only bridge to the live MapLibre projection.
 *
 * The MapLibre `cameraState` lives inside the [MapViewContainer] composable and
 * is unreachable from instrumented test code, so gesture tests historically
 * couldn't convert a lat/lon into a screen pixel (and the old OSMDroid helpers
 * that did were deleted in the MapLibre migration). MapViewContainer installs a
 * resolver here once the map is composed; [MapTestHelper] reads it to drive
 * real gestures at geographic coordinates.
 *
 * Inert in production: the resolver is only ever *read* by test code. It closes
 * over the live cameraState, so each call reflects the current camera.
 */
object MapProjectionTestHook {

    @Volatile
    private var resolver: ((lat: Double, lon: Double) -> Offset?)? = null

    /** Installed by MapViewContainer; pass null on dispose to clear. */
    fun setResolver(r: ((lat: Double, lon: Double) -> Offset?)?) {
        resolver = r
    }

    /**
     * Screen pixel ([Offset], px) for a geographic point, or null if the map
     * isn't composed / the projection isn't ready yet.
     */
    fun screenPxFor(lat: Double, lon: Double): Offset? = resolver?.invoke(lat, lon)

    /** True once a live projection resolver is installed. */
    val isReady: Boolean get() = resolver != null
}
