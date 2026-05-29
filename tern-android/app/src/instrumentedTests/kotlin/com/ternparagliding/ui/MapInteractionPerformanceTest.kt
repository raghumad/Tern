package com.ternparagliding.ui

import org.junit.Ignore
import org.junit.Test

/**
 * Map interaction performance tests using OSMDroid MapView controller.
 *
 * M8: DISABLED. These tests accessed MapViewModel.mapView (the OSMDroid
 * MapView instance) directly to set zoom/center. MapViewModel no longer
 * holds a mapView reference -- the map is MapLibre, driven by Redux
 * dispatch -> CameraState. These tests need to be rewritten to drive
 * the camera via Redux actions and assert via MapStore state.
 */
@Ignore("M8: MapViewModel.mapView deleted during OSMDroid-to-MapLibre migration")
class MapInteractionPerformanceTest {

    @Test
    fun testRapidPanningBoulderToDC() {
        // Placeholder -- see class-level @Ignore
    }

    @Test
    fun testZoomOutToRegionalView() {
        // Placeholder -- see class-level @Ignore
    }

    @Test
    fun testNavigatingNearCountryBorders() {
        // Placeholder -- see class-level @Ignore
    }
}
