package com.ternparagliding.utils

import com.ternparagliding.model.Waypoint
import kotlin.math.roundToInt

/**
 * Utility for encoding/decoding polylines and integers for XCTSK format.
 * Based on Google Polyline Algorithm and XCTrack's integer encoding.
 */
object PolylineUtils {

    /**
     * Encodes a list of coordinates into a polyline string.
     * Precision is 1e5 (standard Google Polyline).
     */
    fun encodePolyline(waypoints: List<Waypoint>): String {
        val result = StringBuilder()
        var lastLat = 0
        var lastLon = 0

        for (point in waypoints) {
            val lat = (point.lat * 1e5).roundToInt()
            val lon = (point.lon * 1e5).roundToInt()

            val dLat = lat - lastLat
            val dLon = lon - lastLon

            encodeValue(dLat, result)
            encodeValue(dLon, result)

            lastLat = lat
            lastLon = lon
        }
        return result.toString()
    }

    private fun encodeValue(value: Int, result: StringBuilder) {
        var v = value shl 1
        if (value < 0) {
            v = v.inv()
        }
        while (v >= 0x20) {
            result.append(((v and 0x1f) or 0x20 + 63).toChar())
            v = v shr 5
        }
        result.append((v + 63).toChar())
    }

    /**
     * Encodes a single integer using the same 5-bit chunk encoding as polyline,
     * but without the delta/zigzag encoding if it's just a raw value?
     * XCTrack uses a specific encoding for altitude/radius in the 'z' string.
     * It seems to use the same variable-length encoding but for single values.
     * 
     * Ref from iOS: encodeSingleInteger -> encodeFiveBitComponents
     */
    fun encodeSingleInteger(value: Int): String {
        var v = value
        // Zigzag encoding
        v = v shl 1
        if (value < 0) {
            v = v.inv()
        }
        return encodeFiveBitComponents(v)
    }

    private fun encodeFiveBitComponents(value: Int): String {
        var v = value
        val result = StringBuilder()
        do {
            var chunk = v and 0x1F
            if (v >= 0x20) {
                chunk = chunk or 0x20
            }
            chunk += 63
            result.append(chunk.toChar())
            v = v shr 5
        } while (v > 0) // Note: iOS loop condition was (remainingComponents != 0)
        // If v was 0 initially, do-while ensures one char is written? 
        // iOS implementation:
        // repeat { ... } while (remainingComponents != 0)
        // If value is 0, loop runs once.
        
        return result.toString()
    }
    
    /**
     * Decodes all encoded integers from the string.
     * Used for XCTSK 'z' string which contains Polyline (Lat, Lon) + Alt + Radius.
     */
    fun decodeValues(encoded: String): List<Int> {
        val values = ArrayList<Int>()
        var index = 0
        val len = encoded.length

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            
            // Zigzag decode
            val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            values.add(value)
        }
        return values
    }

    /**
     * Decodes a polyline string into a list of coordinate pairs (lat, lon).
     */
    fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val values = decodeValues(encoded)
        val poly = ArrayList<Pair<Double, Double>>()
        var lat = 0
        var lng = 0
        
        for (i in 0 until values.size step 2) {
            if (i + 1 < values.size) {
                lat += values[i]
                lng += values[i+1]
                poly.add(Pair(lat / 1e5, lng / 1e5))
            }
        }
        return poly
    }
}
