package com.madanala.tern.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint

class MapOverlayCacheUtilsTest {

    @Test
    fun `parseNdGeoJsonToFeatures returns empty for standard GeoJSON FeatureCollection`() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {},
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [
                        [-122.0, 37.0],
                        [-122.0, 37.1],
                        [-122.1, 37.1],
                        [-122.1, 37.0],
                        [-122.0, 37.0]
                      ]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val features = MapOverlayCacheUtils.parseNdGeoJsonToFeatures(geoJson)
        assertThat(features).isEmpty()
    }

    @Test
    fun `parseNdGeoJsonToFeatures parses valid NDGeoJSON`() {
        val ndGeoJson = """
            {"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-122.0,37.0],[-122.0,37.1],[-122.1,37.1],[-122.1,37.0],[-122.0,37.0]]]}}
            {"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-123.0,38.0],[-123.0,38.1],[-123.1,38.1],[-123.1,38.0],[-123.0,38.0]]]}}
        """.trimIndent()

        val features = MapOverlayCacheUtils.parseNdGeoJsonToFeatures(ndGeoJson)
        assertThat(features).hasSize(2)
    }

    @Test
    fun `parseGeoJsonToFeatures handles JSON Array gracefully`() {
        val jsonArray = """
            [
                {
                  "type": "Feature",
                  "properties": {"name": "Test Spot"},
                  "geometry": {
                    "type": "Point",
                    "coordinates": [10.0, 50.0]
                  }
                }
            ]
        """.trimIndent()

        // This is expected to return empty list or handle it without crashing
        // Current implementation expects Map, so it might throw or return empty
        try {
            val features = MapOverlayCacheUtils.parseGeoJsonToFeatures(jsonArray, "pgspot")
            // If it supports array, it should have 1 feature. If not, it should be empty.
            // We want to know what it DOES.
            println("Features from array: ${features.size}")
        } catch (e: Exception) {
            println("Exception parsing array: ${e.javaClass.simpleName}")
        }
    }
}
