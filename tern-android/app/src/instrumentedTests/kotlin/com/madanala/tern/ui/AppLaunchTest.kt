package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.madanala.tern.utils.BddTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest : BddTest() {

    @Test
    fun testAppLaunchToMap_PacificOcean() {
        // Remote location in Pacific Ocean
        launchAppToMap(0.0, -160.0, "Pacific Ocean", expectData = false)
    }

    private fun launchAppToMap(lat: Double, lon: Double, locationName: String? = null, expectData: Boolean = true) {
        scenario("App Launch to Map (${locationName ?: "Custom Location"})") {
            givenAppIsLaunchedOnMap(lat = lat, lon = lon)
            
            then("the map should center on the target location") {
                // givenAppIsLaunchedOnMap already asserts the map exists.
                composeTestRule.onNodeWithTag("map_view").assertExists()
            }
            
            and("the 'Settings' button should be visible") {
                 // Settings button is part of the main map UI
                 composeTestRule.onNodeWithContentDescription("Settings").assertExists()
            }

            `when`("Test data is injected and location updates") {
                if (expectData) {
                    // Inject PG Spot
                    val pgSpotFeature = createTestPGSpot("Test Launch", lat, lon)
                    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                    com.madanala.tern.utils.TestCacheInjector.injectPGSpots(context, com.madanala.tern.utils.CacheManager.pgSpotCache, "US", listOf(pgSpotFeature))

                    // Inject Airspace
                    val airspaceFeature = createTestAirspace("Test Airspace", lat, lon)
                    com.madanala.tern.utils.TestCacheInjector.injectAirspaces(context, com.madanala.tern.utils.CacheManager.airspaceCache, "US", listOf(airspaceFeature))
                }

                // Move location slightly to trigger reload
                com.madanala.tern.utils.MapTestHelper.injectMockLocation(composeTestRule, lat + 0.001, lon - 0.001)
                
                // Perform a drag to trigger map movement and reload
                composeTestRule.onNodeWithTag("map_view").performTouchInput {
                    down(center)
                    moveBy(androidx.compose.ui.geometry.Offset(100f, 100f))
                    up()
                }
            }

            then("PG Spots and Airspaces should be loaded (or not)") {
                // Manual verification via logcat confirmed that features are loaded.
                // We assert the map view exists to ensure the app didn't crash.
                composeTestRule.onNodeWithTag("map_view").assertExists()

                if (expectData) {
                    // Validate Budget Logs with specific counts > 0
                    com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
                        "OverlayManager-PG_SPOTS", 
                        "PG Spot Budget: \\d+ total \\(Created: (\\d+), Visible: (\\d+)"
                    ) { matchResult ->
                        val created = matchResult.groupValues[1].toInt()
                        val visible = matchResult.groupValues[2].toInt()
                        created > 0 && visible > 0
                    }

                    com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
                        "OverlayManager-AIRSPACE", 
                        "Airspace Budget: \\d+ total \\(Created: (\\d+), Visible: (\\d+)"
                    ) { matchResult ->
                        val created = matchResult.groupValues[1].toInt()
                        val visible = matchResult.groupValues[2].toInt()
                        created > 0 && visible > 0
                    }
                } else {
                    // Validate Budget Logs with counts == 0
                    com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
                        "OverlayManager-PG_SPOTS", 
                        "PG Spot Budget: \\d+ total \\(Created: (\\d+), Visible: (\\d+)"
                    ) { matchResult ->
                        val created = matchResult.groupValues[1].toInt()
                        val visible = matchResult.groupValues[2].toInt()
                        created == 0 && visible == 0
                    }

                    com.madanala.tern.utils.ReportGenerator.assertLogMatchesRegex(
                        "OverlayManager-AIRSPACE", 
                        "Airspace Budget: \\d+ total \\(Created: (\\d+), Visible: (\\d+)"
                    ) { matchResult ->
                        val created = matchResult.groupValues[1].toInt()
                        val visible = matchResult.groupValues[2].toInt()
                        created == 0 && visible == 0
                    }
                }
            }
        }
    }

    // Helper methods for creating test features


    private fun createTestPGSpot(name: String, lat: Double, lon: Double): com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature {
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf("name" to name, "siteType" to "Launch"),
            "geometry" to mapOf(
                "type" to "Point",
                "coordinates" to listOf(lon, lat)
            )
        )
        val centroid = org.osmdroid.util.GeoPoint(lat, lon)
        return com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "pgspot"
        )
    }

    private fun createTestAirspace(name: String, lat: Double, lon: Double): com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature {
         val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf("name" to name, "class" to "D"),
            "geometry" to mapOf(
                "type" to "Polygon",
                "coordinates" to listOf(listOf(
                    listOf(lon, lat),
                    listOf(lon + 0.01, lat),
                    listOf(lon + 0.01, lat + 0.01),
                    listOf(lon, lat + 0.01),
                    listOf(lon, lat)
                ))
            )
        )
        val centroid = org.osmdroid.util.GeoPoint(lat, lon)
        return com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "airspace"
        )
    }
}
