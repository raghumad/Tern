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
        server.shutdown()
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
}
