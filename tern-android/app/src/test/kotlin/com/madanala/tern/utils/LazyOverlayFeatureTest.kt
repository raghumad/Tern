package com.madanala.tern.utils

import org.junit.Test
import org.junit.Assert.*
import org.osmdroid.util.GeoPoint
import java.nio.ByteBuffer

class LazyOverlayFeatureTest {

    @Test
    fun testLazyFeatureIntegrity() {
        val properties = mapOf("class" to "C", "name" to "Boulder")
        val centroid = GeoPoint(40.0, -105.0)
        
        // 1. Create a feature and serialize it
        val builder = com.google.flatbuffers.FlexBuffersBuilder()
        val mapStart = builder.startMap()
        
        val featureMapStart = builder.startMap()
        properties.forEach { (k, v) -> builder.putString(k, v as String) }
        builder.endMap("feature", featureMapStart)
        
        val centroidMapStart = builder.startMap()
        builder.putFloat("latitude", centroid.latitude.toFloat())
        builder.putFloat("longitude", centroid.longitude.toFloat())
        builder.endMap("centroid", centroidMapStart)
        
        builder.putInt("hilbertIndex", 123)
        builder.putString("overlayType", "airspace")
        builder.putString("id", "test-id")
        
        builder.endMap(null, mapStart)
        val buffer = builder.finish()
        
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        
        // 2. Wrap in LazyOverlayFeature
        val lazyFeature = MapOverlayCacheUtils.OverlayFeature(
            id = "test-id",
            centroid = centroid,
            hilbertIndex = 123,
            overlayType = "airspace",
            rawData = ByteBuffer.wrap(data)
        )
        
        // 3. Verify lazy properties
        assertEquals("test-id", lazyFeature.id)
        assertEquals("airspace", lazyFeature.overlayType)
        assertEquals("C", lazyFeature.getStringProperty("class"))
        assertEquals("Boulder", lazyFeature.getStringProperty("name"))
        
        // 4. Verify full feature map
        val hydratedMap = lazyFeature.feature
        assertEquals("C", hydratedMap["class"])
        assertEquals("Boulder", hydratedMap["name"])
    }
}
