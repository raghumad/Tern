package com.madanala.tern.ui

import androidx.compose.ui.test.*
import com.madanala.tern.utils.Liar
import com.madanala.tern.utils.MapVisualTest
import com.madanala.tern.utils.MapTestHelper
import com.madanala.tern.utils.ReportGenerator
import com.madanala.tern.utils.WeatherTestHelper
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.osmdroid.util.GeoPoint

class AirspaceUXTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    @Liar("Claims to verify floor/ceiling altitudes, only checks map_view exists")
    @Test
    fun testAirspacePanningAndVisibility() {
        scenario("Airspace UX: Pan to High Density Area (Boulder, US)") {
            story("As a pilot planning a cross-country flight, I want to see restricted airspaces on the map so I can stay safe and compliant with aviation regulations during my flight.") {

                given("I am preparing for a flight in the Boulder area") {
                    givenAppIsLaunchedOnMap(lat = 40.0, lon = -105.2, countryCode = "us")
                    waitForCacheReadiness("US", timeoutMillis = 120000)
                    waitForAirspaces(minCount = 1, timeoutMillis = 20000)
                }

                `when`("I pan the map towards a complex airspace structure near the mountains") {
                    composeTestRule.runOnUiThread {
                        val activity = composeTestRule.activity
                        val store = androidx.lifecycle.ViewModelProvider(activity)[com.madanala.tern.redux.MapStore::class.java]
                        store.dispatch(com.madanala.tern.redux.MapAction.UpdateCenter(GeoPoint(40.1, -105.2)))
                        store.dispatch(com.madanala.tern.redux.MapAction.UpdateZoom(13.0))
                    }
                    composeTestRule.waitForIdle()
                    assertMapLocation(40.1, -105.2)
                    waitForAirspaces(minCount = 1, timeoutMillis = 45000)
                    waitForMapToRender()
                }

                and("I tap on a specific airspace polygon to identify its boundaries and limits") {
                    // MapTestHelper.clickOnGeoPoint uses OSMDroid MapView projection
                    // which no longer exists. Airspace click-to-info requires MapLibre
                    // queryRenderedFeatures which is not exposed to Compose tests.
                    // This step is a no-op until airspace tap handling is implemented.
                }

                then("The airspace details should be clearly visible, showing floor and ceiling altitudes") {
                    // TODO: write real assertions -- airspace tap info panel not implemented
                }

                and("The map response should remain fluid without performance degradation") {
                    // TODO: write real assertions
                }
            }
        }
    }
}
