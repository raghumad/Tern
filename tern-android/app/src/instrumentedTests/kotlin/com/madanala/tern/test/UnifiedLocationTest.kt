package com.madanala.tern.test

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.madanala.tern.TernParaglidingActivity
import com.madanala.tern.model.LocationSource
import com.madanala.tern.model.LocationType
import com.madanala.tern.model.Waypoint
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import org.junit.Rule
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Instrumentation test for Unified Location Model and LocationMarker.
 * Verifies RFC 005 compliance across Waypoints and PG Spots.
 */
class UnifiedLocationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<TernParaglidingActivity>()

    @Test
    fun testLocationMarkerRFC005Compliance() {
        // This test leverages the LocationMarker directly in a controlled composition
        // to verify visual states without full map orchestration.
        
        val waypoint = Waypoint(
            lat = 45.9237,
            lon = 6.8694,
            type = LocationType.LAUNCH,
            label = "Chamonix Launch"
        )
        
        val pgSpot = OverlayFeature(
            internalId = "pg_123",
            feature = mapOf("name" to "Chamonix PG Spot"),
            centroid = GeoPoint(45.9237, 6.8694),
            hilbertIndex = 0L,
            overlayType = "launch"
        )

        composeTestRule.setContent {
            com.madanala.tern.ui.components.LocationMarker(
                location = waypoint,
                zoom = 12.0, // High Zoom -> Full Detail
                forecast = null,
                isSelected = false,
                onClick = {}
            )
        }

        // Verify Label presence in High Zoom
        composeTestRule.onNodeWithText("Chamonix Launch").assertIsDisplayed()
        
        // Switch to Mid Zoom
        composeTestRule.setContent {
            com.madanala.tern.ui.components.LocationMarker(
                location = waypoint,
                zoom = 8.0, // Mid Zoom -> Simplified
                forecast = null,
                isSelected = false,
                onClick = {}
            )
        }
        
        // Label should be hidden in Mid Zoom (RFC 005 Decluttering)
        composeTestRule.onNodeWithText("Chamonix Launch").assertDoesNotExist()

        // Verify PG Spot rendering (Unification Proof)
        composeTestRule.setContent {
            com.madanala.tern.ui.components.LocationMarker(
                location = pgSpot,
                zoom = 12.0,
                forecast = null,
                isSelected = false,
                onClick = {}
            )
        }
        
        // PG Spot should also show label derived from feature (Unification check)
        composeTestRule.onNodeWithText("Chamonix PG Spot").assertIsDisplayed()
    }

    @Test
    fun testHazardVisualizationRFC005() {
        // Verify that thunderstorm hazard (Lightning Bolt) is rendered per RFC 005
        
        val waypoint = Waypoint(
            lat = 45.9237,
            lon = 6.8694,
            type = LocationType.LAUNCH,
            label = "Stormy Launch"
        )
        
        // Create a mock forecast with thunderstorm risk (>60% lightning potential)
        val stormyForecast = com.madanala.tern.utils.WeatherForecast(
            current = com.madanala.tern.utils.WeatherData(
                wind = com.madanala.tern.utils.WindData(10.0, 180.0, 15.0),
                temperature = 20.0,
                humidity = 90.0,
                visibility = 5.0,
                pressure = 1010.0,
                cloudCover = 95.0,
                timestamp = System.currentTimeMillis(),
                lightningPotential = 85.0 // High risk!
            ),
            daily = emptyList(),
            hourly = emptyList()
        )

        composeTestRule.setContent {
            com.madanala.tern.ui.components.LocationMarker(
                location = waypoint,
                zoom = 12.0,
                forecast = stormyForecast,
                isSelected = false,
                onClick = {}
            )
        }

        // Verify the lightning bolt is present (using testTag implemented in LocationMarker.kt)
        composeTestRule.onNodeWithTag("HazardBolt_Stormy Launch").assertIsDisplayed()
    }
}
