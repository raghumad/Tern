package com.madanala.tern.utils

import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.osmdroid.util.GeoPoint

import com.madanala.tern.utils.WeatherForecast as UtilsForecast

class TrajectoryAnalyzerTest {

    private val weatherCache = mockk<WeatherCache>(relaxed = true)
    private val weatherAPI = mockk<WeatherAPI>(relaxed = true)
    private val analyzer = TrajectoryAnalyzer(weatherCache, weatherAPI)

    @Test
    fun `analyzeTrajectory calculates correct ETAs and fetches weather`() = runBlocking {
        // Given a route with 2 waypoints (approx 30km apart -> 1 hour flight)
        val start = Waypoint(lat = 40.0, lon = -105.0, type = Waypoint.Type.LAUNCH, routeId = "r1")
        val end = Waypoint(lat = 40.27, lon = -105.0, type = Waypoint.Type.LANDING, routeId = "r1") // ~30km north
        val route = Route.fromWaypoints("Test Route", listOf(start, end))
        
        val startTime = 1000000L
        
        // Mock Weather API
        // Use real instance instead of mock for data class
        val mockForecast = UtilsForecast(
            current = null,
            daily = emptyList(),
            hourly = emptyList()
        )
        coEvery { weatherAPI.isAvailable() } returns true
        coEvery { weatherAPI.fetchForecast(any<Double>(), any<Double>()) } returns mockForecast
        every { weatherCache.queryNearbyWeather(any<Double>(), any<Double>(), any<Double>()) } returns null // Cache miss

        // When
        val result = analyzer.analyzeTrajectory(route, startTime)

        // Then
        assertNotNull(result.trajectoryForecast)
        assertEquals(2, result.trajectoryForecast?.waypoints?.size)
        
        val wp1 = result.trajectoryForecast!!.waypoints[0]
        assertEquals(startTime, wp1.estimatedArrival)
        
        val wp2 = result.trajectoryForecast!!.waypoints[1]
        // 30km @ 30km/h = 1 hour = 3600000 ms
        // Allow some margin for calculation precision
        val expectedDuration = 3600000L
        val diff = kotlin.math.abs((wp2.estimatedArrival - startTime) - expectedDuration)
        assert(diff < 60000) { "ETA difference too large: $diff ms" }
    }
}
