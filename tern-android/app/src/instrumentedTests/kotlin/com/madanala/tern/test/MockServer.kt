package com.madanala.tern.test

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Helper class for managing MockWebServer in tests.
 * Framework-agnostic implementation.
 */
class MockServer {

    val server = MockWebServer()

    fun start() {
        server.start()
    }

    fun shutdown() {
        try {
            server.shutdown()
        } catch (e: Exception) {
            android.util.Log.e("MockServer", "Error shutting down MockWebServer: ${e.message}")
        }
    }

    fun setDispatcher(dispatcher: okhttp3.mockwebserver.Dispatcher) {
        server.dispatcher = dispatcher
    }

    fun url(path: String) = server.url(path).toString()

    fun enqueueResponse(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setBody(body)
        )
    }

    fun enqueueWeatherResponse(temperature: Double = 20.0, windSpeed: Double = 10.0) {
        val json = """
            {
                "current_weather": {
                    "temperature": $temperature,
                    "windspeed": $windSpeed,
                    "winddirection": 180,
                    "weathercode": 0,
                    "time": "2023-01-01T12:00"
                }
            }
        """.trimIndent()
        enqueueResponse(json)
    }

    fun enqueueAirspaceResponse(count: Int = 1) {
        val features = (1..count).joinToString(",") { id ->
            """
            {
                "type": "Feature",
                "properties": {
                    "name": "Restricted Area $id",
                    "class": "R",
                    "lower_limit": 0,
                    "upper_limit": 5000
                },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0,0], [0,1], [1,1], [1,0], [0,0]]]
                }
            }
            """.trimIndent()
        }
        
        val json = """
            {
                "type": "FeatureCollection",
                "features": [$features]
            }
        """.trimIndent()
        enqueueResponse(json)
    }
    fun enqueuePGSpotsResponse(count: Int = 1) {
        val json = generatePGSpotsJson(count)
        enqueueResponse(json)
    }

    fun setPGSpotsDispatcher(count: Int = 1) {
        val pgSpotsJson = generatePGSpotsJson(count)
        val airspacesJson = generateAirspacesJson(count)
        
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                android.util.Log.i("MockServer", "RECEIVED REQUEST: ${request.path} from ${request.requestUrl}")
                println("DEBUG: MockServer Dispatcher received request: ${request.path}")
                if (request.path?.contains("getCountrySites.php") == true) {
                    android.util.Log.i("MockServer", "Serving mock PG spots for: ${request.path}")
                    return MockResponse().setResponseCode(200).setBody(pgSpotsJson)
                }
                if (request.path?.contains("_asp.geojson") == true) {
                    android.util.Log.i("MockServer", "Serving mock airspaces for: ${request.path}")
                    return MockResponse().setResponseCode(200).setBody(airspacesJson)
                }
                return MockResponse().setResponseCode(404)
            }
        }
    }

    private fun generateAirspacesJson(count: Int): String {
        return (1..count).joinToString("\n") { id ->
            // Create a single-line JSON feature
            """{"type":"Feature","properties":{"name":"Restricted Area $id","class":"R","floor":0,"ceiling":5000,"country":"US"},"geometry":{"type":"Polygon","coordinates":[[[-105.27,40.01],[-105.26,40.01],[-105.26,40.02],[-105.27,40.02],[-105.27,40.01]]]}}"""
        }
    }

    private fun generatePGSpotsJson(count: Int): String {
        val features = (1..count).joinToString(",") { id ->
            """
            {
                "type": "Feature",
                "properties": {
                    "name": "Test Launch $id",
                    "siteType": "Launch"
                },
                "geometry": {
                    "type": "Point",
                    "coordinates": [-105.2705, 40.0150]
                }
            }
            """.trimIndent()
        }
        
        return """
            {
                "type": "FeatureCollection",
                "features": [$features]
            }
        """.trimIndent()
    }
}
