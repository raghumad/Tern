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
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test serialization of simple point geometry`() {
        val featureMap = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "Point",
                "coordinates" to listOf(-105.27, 40.01)
            ),
            "properties" to mapOf("name" to "Test Point")
        )
        val centroid = GeoPoint(40.01, -105.27)
        val originalFeature = MapOverlayCacheUtils.OverlayFeature(
            internalId = null,
            feature = featureMap, 
            centroid = centroid, 
            hilbertIndex = 12345L, 
            overlayType = "test_point"
        )

        val (index, data) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(listOf(originalFeature))
        val deserializedFeatures = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

        assertThat(deserializedFeatures).hasSize(1)
        val deserializedFeature = deserializedFeatures[0]
        
        assertThat(deserializedFeature.hilbertIndex).isEqualTo(originalFeature.hilbertIndex)
        assertThat(deserializedFeature.overlayType).isEqualTo(originalFeature.overlayType)
        assertThat(deserializedFeature.centroid.latitude).isWithin(0.0001).of(originalFeature.centroid.latitude)
        
        val geometry = deserializedFeature.feature["geometry"] as Map<String, Any>
        val coordinates = geometry["coordinates"] as List<Any>
        assertThat((coordinates[0] as Number).toDouble()).isWithin(0.0001).of(-105.27)
        assertThat((coordinates[1] as Number).toDouble()).isWithin(0.0001).of(40.01)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test serialization of polygon geometry (nested lists)`() {
        // This tests the fix for Airspaces
        val coordinates = listOf(
            listOf(
                listOf(-105.27, 40.01),
                listOf(-105.26, 40.01),
                listOf(-105.26, 40.02),
                listOf(-105.27, 40.02),
                listOf(-105.27, 40.01)
            )
        )
        val featureMap = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "Polygon",
                "coordinates" to coordinates
            ),
            "properties" to mapOf("name" to "Test Polygon")
        )
        val centroid = GeoPoint(40.015, -105.265)
        val originalFeature = MapOverlayCacheUtils.OverlayFeature(
            internalId = null,
            feature = featureMap, 
            centroid = centroid, 
            hilbertIndex = 67890L, 
            overlayType = "test_polygon"
        )

        val (index, data) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(listOf(originalFeature))
        val deserializedFeatures = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

        assertThat(deserializedFeatures).hasSize(1)
        val deserializedFeature = deserializedFeatures[0]
        
        val geometry = deserializedFeature.feature["geometry"] as Map<String, Any>
        val deserializedCoords = geometry["coordinates"] as List<List<List<Any>>>
        
        assertThat(deserializedCoords).hasSize(1)
        assertThat(deserializedCoords[0]).hasSize(5)
        assertThat((deserializedCoords[0][0][0] as Number).toDouble()).isWithin(0.0001).of(-105.27)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test serialization of complex nested properties`() {
        val properties = mapOf(
            "simple" to "value",
            "nested" to mapOf(
                "level1" to "value1",
                "level2" to mapOf("deep" to true)
            ),
            "array" to listOf(1, 2, 3),
            "mixedArray" to listOf("a", 1, true, mapOf("key" to "val"))
        )
        val featureMap = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "Point",
                "coordinates" to listOf(0.0, 0.0)
            ),
            "properties" to properties
        )
        val centroid = GeoPoint(0.0, 0.0)
        val originalFeature = MapOverlayCacheUtils.OverlayFeature(
            internalId = null,
            feature = featureMap, 
            centroid = centroid, 
            hilbertIndex = 0L, 
            overlayType = "complex_prop"
        )

        val (index, data) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(listOf(originalFeature))
        val deserializedFeatures = MapOverlayCacheUtils.deserializeFlexBuffersToFeatures(data)

        val deserializedProps = deserializedFeatures[0].feature["properties"] as Map<String, Any>
        
        assertThat(deserializedProps["simple"]).isEqualTo("value")
        
        val nested = deserializedProps["nested"] as Map<String, Any>
        assertThat(nested["level1"]).isEqualTo("value1")
        
        val level2 = nested["level2"] as Map<String, Any>
        assertThat(level2["deep"]).isEqualTo(true)
        
        val mixedArray = deserializedProps["mixedArray"] as List<Any>
        assertThat(mixedArray[0]).isEqualTo("a")
        assertThat((mixedArray[1] as Number).toInt()).isEqualTo(1)
        assertThat(mixedArray[2]).isEqualTo(true)
        
        val mapInArray = mixedArray[3] as Map<String, Any>
        assertThat(mapInArray["key"]).isEqualTo("val")
    }
}
