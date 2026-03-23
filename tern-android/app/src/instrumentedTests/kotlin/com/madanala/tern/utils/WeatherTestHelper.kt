package com.madanala.tern.utils

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/**
 * Helper to manage MockWebServer for Weather API testing.
 * Provides standard aviation-grade weather responses.
 */
object WeatherTestHelper {
    private var server: MockWebServer? = null

    fun startServer(): String {
        server = MockWebServer()
        server?.start()
        val url = server?.url("/v1/forecast")?.toString() ?: ""
        
        // Aviation-grade default dispatcher: Ensure airspaces and spots are ALWAYS mocked
        // even if the test doesn't call setDispatcher explicitly.
        setDefaultDispatcher()
        
        OpenMeteoWeatherAPI.setBaseUrlForTesting(url)
        PGSpotCache.setBaseUrlForTesting(url)
        try {
            CacheManager.airspaceCache.setBaseUrlForTesting(url)
        } catch (e: Exception) {}
        return url
    }

    private fun setDefaultDispatcher() {
        // [SOURCE OF TRUTH] Default mock data for all tests
        val boulderSpots = listOf(
            mapOf("id" to "pg_boulder_test", "name" to "Boulder Launch", "lat" to 40.015, "lon" to -105.27)
        )
        val airspaceBody = """{"type":"Feature","properties":{"name":"Mock Airspace","class":"D"},"geometry":{"type":"Polygon","coordinates":[[[-105.3,40.1],[-105.2,40.1],[-105.2,40.0],[-105.3,40.0],[-105.3,40.1]]]}}""" + "\n"

        server?.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val requestPath = request.path ?: ""
                return when {
                    requestPath.contains("getCountrySites.php") -> {
                        val body = boulderSpots.joinToString("\n") { spot ->
                            """{"type":"Feature","id":"${spot["id"]}","geometry":{"type":"Point","coordinates":[${spot["lon"]},${spot["lat"]}]},"properties":{"id":"${spot["id"]}","name":"${spot["name"]}"}}"""
                        } + "\n"
                        MockResponse().setBody(body).setHeader("Content-Type", "application/x-ndgeojson").setResponseCode(200)
                    }
                    requestPath.contains("_asp.geojson") -> {
                        MockResponse().setBody(airspaceBody).setHeader("Content-Type", "application/x-ndgeojson").setResponseCode(200)
                    }
                    requestPath.contains("forecast") -> {
                        // Return a minimal valid weather JSON as default
                        MockResponse().setBody("""{"latitude":40.0,"longitude":-105.0,"hourly":{"time":[],"temperature_2m":[],"relative_humidity_2m":[],"wind_speed_10m":[],"wind_direction_10m":[]}}""").setResponseCode(200)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    fun stopServer() {
        server?.shutdown()
        server = null
        OpenMeteoWeatherAPI.resetBaseUrl()
        PGSpotCache.resetBaseUrlForTesting()
        try {
            CacheManager.airspaceCache.resetBaseUrlForTesting()
        } catch (e: Exception) {}
    }

    fun setDispatcher(speed: Double, direction: Double) {
        val weatherJson = generateWeatherJson(speed, direction, speed * 1.3)
        // [RFC 005] Multiple spots for stress testing
        val boulderSpots = listOf(
            mapOf("id" to "pg_boulder_test", "name" to "Boulder Launch", "lat" to 40.015, "lon" to -105.27)
        )
        
        val chamonixSpots = listOf(
            mapOf("id" to "chx_1", "name" to "Planpraz", "lat" to 45.936, "lon" to 6.852),
            mapOf("id" to "chx_2", "name" to "Brévent", "lat" to 45.934, "lon" to 6.837),
            mapOf("id" to "chx_3", "name" to "Flégère", "lat" to 45.961, "lon" to 6.887),
            mapOf("id" to "chx_4", "name" to "Les Grands Montets", "lat" to 45.979, "lon" to 6.946),
            mapOf("id" to "chx_5", "name" to "Aiguille du Midi", "lat" to 45.879, "lon" to 6.887)
        )

        server?.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val requestPath = request.path ?: ""
                val url = request.requestUrl
                return when {
                    requestPath.contains("getCountrySites.php") -> {
                        val country = url?.queryParameter("iso") ?: "US"
                        val spots = if (country.equals("FR", ignoreCase = true)) chamonixSpots else boulderSpots
                        val body = spots.joinToString("\n") { spot ->
                            val lon = spot["lon"]
                            val lat = spot["lat"]
                            val id = spot["id"]
                            val name = spot["name"]
                            """{"type":"Feature","id":"$id","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{"id":"$id","name":"$name"}}"""
                        } + "\n"
                        MockResponse().setBody(body).setHeader("Content-Type", "application/x-ndgeojson").setResponseCode(200)
                    }
                    requestPath.contains("_asp.geojson") -> {
                        val body = """{"type":"Feature","properties":{"name":"Mock Airspace","class":"D"},"geometry":{"type":"Polygon","coordinates":[[[-105.3,40.1],[-105.2,40.1],[-105.2,40.0],[-105.3,40.0],[-105.3,40.1]]]}}""" + "\n"
                        MockResponse().setBody(body).setHeader("Content-Type", "application/x-ndgeojson").setResponseCode(200)
                    }
                    requestPath.endsWith("forecast") || requestPath.contains("forecast?") -> {
                        MockResponse().setBody(weatherJson).setResponseCode(200)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    fun enqueueWeatherResponse(speed: Double, direction: Double, gust: Double = speed * 1.3) {
        val json = generateWeatherJson(speed, direction, gust)
        server?.enqueue(MockResponse().setBody(json).setResponseCode(200))
    }

    private fun generateWeatherJson(speed: Double, direction: Double, gust: Double): String {
        val map = generateWeatherMap(speed, direction, gust)
        return com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(map)
    }

    private fun generateWeatherMap(speed: Double, direction: Double, gust: Double): Map<String, Any> {
        val now = java.time.LocalDateTime.now()
        val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val times = (0 until 48).map { now.plusHours(it.toLong()).format(formatter) }
        
        return mapOf(
            "latitude" to 40.0,
            "longitude" to -105.0,
            "hourly" to mapOf(
                "time" to times,
                "temperature_2m" to List(48) { 15.0 },
                "relative_humidity_2m" to List(48) { 45.0 },
                "wind_speed_10m" to List(48) { speed },
                "wind_direction_10m" to List(48) { direction },
                "wind_speed_80m" to List(48) { speed }, // Unified for testing
                "wind_direction_80m" to List(48) { direction },
                "wind_gusts_10m" to List(48) { gust },
                "pressure_msl" to List(48) { 1013.25 },
                "cloud_cover" to List(48) { 0.0 },
                "visibility" to List(48) { 10000.0 }
            ),
            "daily" to mapOf(
                "time" to (0 until 7).map { now.plusDays(it.toLong()).toLocalDate().toString() },
                "temperature_2m_max" to List(7) { 20.0 },
                "temperature_2m_min" to List(7) { 10.0 },
                "wind_speed_10m_max" to List(7) { speed },
                "wind_direction_10m_dominant" to List(7) { direction }
            )
        )
    }
}
