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
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                android.util.Log.i("MockServer", "RECEIVED REQUEST: ${request.path} from ${request.requestUrl}")
                val path = request.path ?: return MockResponse().setResponseCode(404)
                
                // Extract ISO code from query parameters or filename
                val iso = if (path.contains("iso=")) {
                    path.substringAfter("iso=").substringBefore("&").uppercase()
                } else if (path.contains("_asp.geojson")) {
                    path.substringAfterLast("/").substringBefore("_asp.geojson").uppercase()
                } else {
                    "US"
                }

                // Chamonix coordinates for FR, Boulder for others
                val coords = if (iso == "FR") {
                    doubleArrayOf(6.8694, 45.9237)
                } else {
                    doubleArrayOf(-105.2705, 40.0150)
                }

                if (path.contains("getCountrySites.php")) {
                    android.util.Log.i("MockServer", "Serving mock PG spots for $iso at ${coords[1]},${coords[0]}")
                    return MockResponse().setResponseCode(200).setBody(generatePGSpotsJson(count, coords[1], coords[0]))
                }
                if (path.contains("_asp.geojson")) {
                    android.util.Log.i("MockServer", "Serving mock airspaces for $iso at ${coords[1]},${coords[0]}")
                    return MockResponse().setResponseCode(200).setBody(generateAirspacesJson(count, coords[1], coords[0]))
                }
                return MockResponse().setResponseCode(404)
            }
        }
    }

    private fun generateAirspacesJson(count: Int, lat: Double = 40.01, lon: Double = -105.27): String {
        return (1..count).joinToString("\n") { id ->
            // Create a single-line JSON feature centered around requested coords
            """{"type":"Feature","properties":{"name":"Restricted Area $id","class":"R","floor":0,"ceiling":5000,"country":"US"},"geometry":{"type":"Polygon","coordinates":[[[${lon-0.01},${lat-0.005}],[${lon+0.01},${lat-0.005}],[${lon+0.01},${lat+0.005}],[${lon-0.01},${lat+0.005}],[${lon-0.01},${lat-0.005}]]]}}"""
        }
    }

    private fun generatePGSpotsJson(count: Int, lat: Double = 40.0150, lon: Double = -105.2705): String {
        val features = (1..count).joinToString(",") { id ->
            // Distribute spots slightly around center
            val offsetLat = (id % 5) * 0.002
            val offsetLon = (id / 5) * 0.002
            """
            {
                "type": "Feature",
                "properties": {
                    "name": "Test Launch $id",
                    "siteType": "Launch"
                },
                "geometry": {
                    "type": "Point",
                    "coordinates": [${lon + offsetLon}, ${lat + offsetLat}]
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
