package com.ternparagliding.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GeoJsonUtilsTest {

    @Test
    fun `isNdGeoJson returns false for standard GeoJSON FeatureCollection`() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": []
            }
        """.trimIndent()
        assertThat(GeoJsonUtils.isNdGeoJson(geoJson)).isFalse()
    }

    @Test
    fun `isNdGeoJson returns false for minified standard GeoJSON`() {
        val geoJson = """{"type":"FeatureCollection","features":[]}"""
        assertThat(GeoJsonUtils.isNdGeoJson(geoJson)).isFalse()
    }

    @Test
    fun `isNdGeoJson returns false for standard GeoJSON Feature`() {
        val geoJson = """
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [0,0]},
              "properties": {}
            }
        """.trimIndent()
        assertThat(GeoJsonUtils.isNdGeoJson(geoJson)).isFalse()
    }

    @Test
    fun `isNdGeoJson returns true for multi-line NDGeoJSON`() {
        val ndGeoJson = """
            {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}
            {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[1,1]}}
        """.trimIndent()
        assertThat(GeoJsonUtils.isNdGeoJson(ndGeoJson)).isTrue()
    }

    @Test
    fun `isNdGeoJson returns true for single-line NDGeoJSON with multiple features`() {
        val ndGeoJson = """{"type":"Feature"}{"type":"Feature"}"""
        // Even if weirdly formatted without newline, if it's multiple objects it should be handled
        assertThat(GeoJsonUtils.isNdGeoJson(ndGeoJson)).isTrue()
    }

    @Test
    fun `isNdGeoJson returns false for empty or blank string`() {
        assertThat(GeoJsonUtils.isNdGeoJson("")).isFalse()
        assertThat(GeoJsonUtils.isNdGeoJson("   ")).isFalse()
    }

    @Test
    fun `parseGeoJson correctly handles standard GeoJSON`() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [8.0, 47.0]},
                  "properties": {"name": "Test Point"}
                }
              ]
            }
        """.trimIndent()
        
        val features = OverlayGeoJsonParser.parseGeoJsonToFeatures(geoJson, "test_type")
        assertThat(features).hasSize(1)
        assertThat(features[0].overlayType).isEqualTo("test_type")
        assertThat(features[0].centroid.latitude).isEqualTo(47.0)
        assertThat(features[0].centroid.longitude).isEqualTo(8.0)
    }

    @Test
    fun `parseGeoJson correctly handles NDGeoJSON`() {
        val ndGeoJson = """
            {"type":"Feature","geometry":{"type":"Point","coordinates":[8.1, 47.1]},"properties":{}}
            {"type":"Feature","geometry":{"type":"Point","coordinates":[8.2, 47.2]},"properties":{}}
        """.trimIndent()
        
        val features = OverlayGeoJsonParser.parseNdGeoJsonToFeatures(ndGeoJson, "test_type")
        assertThat(features).hasSize(2)
        assertThat(features[0].centroid.latitude).isEqualTo(47.1)
        assertThat(features[1].centroid.latitude).isEqualTo(47.2)
    }
}
