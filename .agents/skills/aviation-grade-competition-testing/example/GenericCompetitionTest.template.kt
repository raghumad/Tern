package com.madanala.tern.test

import com.madanala.tern.model.Waypoint
import com.madanala.tern.model.Route
import com.madanala.tern.utils.MapVisualTest
import org.junit.Test

/**
 * GENERIC COMPETITION TEST TEMPLATE
 * 
 * Follow Section 1 of the 'Aviation-Grade Competition Testing' skill 
 * to deduce data before filling this template.
 */
class GenericCompetitionTest : MapVisualTest() {

    @Test
    fun pilot_flies_competition_task() {
        // 1. DEDUCE AND MODEL
        val competitionRoute = Route(
            name = "Competition Name - Task X",
            waypoints = listOf(
                Waypoint("LAUNCH", 0.0, 0.0, radius = 400f, type = "Launch"),
                Waypoint("SSS", 0.0, 0.0, radius = 30000f, type = "Start Speed Section"),
                Waypoint("TP1", 0.0, 0.0, radius = 400f, type = "Turnpoint"),
                Waypoint("GOAL", 0.0, 0.0, radius = 100f, type = "Goal")
            )
        )

        scenario("Flying Competition Task X") {
            story("Pilot navigates through a verified competition route") {
                
                given("the pilot is at Launch", takeScreenshot = true) {
                    givenAppIsLaunchedOnMap(lat = 0.0, lon = 0.0, countryCode = "XX")
                    zoomTo(0.0, 0.0, 12.0)
                    showRouteOnMap(competitionRoute)
                    waitForMapToRender(3000)

                    // Verifiable GIVEN
                    assertMapLocation(0.0, 0.0)
                    assertZoomLevel(12.0)
                    assertRoutePresence(competitionRoute.name)
                }

                then("the HUD should show telemetry to SSS") {
                    // Add assertions for HUD distance, ETAs, etc.
                }

                // Add more steps for TP1, ESS, Goal...
            }
        }
    }
}
