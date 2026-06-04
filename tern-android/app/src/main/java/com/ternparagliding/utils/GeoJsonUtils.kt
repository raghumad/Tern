@file:Suppress("SENSELESS_COMPARISON", "DEPRECATION")
package com.ternparagliding.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import java.io.IOException

object GeoJsonUtils {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)    // Increased from 10s
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)       // Increased from 10s for large files
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)      // Increased from 10s
        .retryOnConnectionFailure(true)                               // Enable automatic retries
        .build()
    private val mapper = jacksonObjectMapper()

    /**
     * Download GeoJSON data from a URL with proper resource management and validation
     * @param url The URL to download from
     * @return The GeoJSON data as a string, or null if download failed or content is invalid
     */
    suspend fun downloadGeoJson(url: String, userAgent: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.i("GeoJsonUtils", "Executing HTTP download for: $url")
                val requestBuilder = Request.Builder().url(url)
                if (userAgent != null) {
                    requestBuilder.header("User-Agent", userAgent)
                }
                val request = requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val data = body.string()
                            Log.d("GeoJsonUtils", "Downloaded ${data.length} bytes from $url")

                            // VALIDATE: Check if downloaded content is valid JSON/GeoJSON
                            if (validateGeoJsonContent(data, url)) {
                                Log.d("GeoJsonUtils", "✅ Downloaded content validated for $url")
                                data
                            } else {
                                Log.w("GeoJsonUtils", "❌ Downloaded content failed validation for $url")
                                null
                            }
                        } else {
                            Log.w("GeoJsonUtils", "Response body is null for $url")
                            null
                        }
                    } else {
                        Log.w("GeoJsonUtils", "Failed to download from $url: ${response.code} ${response.message}")
                        null
                    }
                }
            } catch (e: IOException) {
                Log.w("GeoJsonUtils", "IOException downloading $url: ${e.message}")
                null
            }
        }
    }

    /**
     * Download GeoJSON data and stream features one by one directly from the byte stream.
     * This avoids massive String/Map allocations in the JVM Heap.
     */
    suspend fun streamGeoJsonFeatures(url: String, processFeature: (Map<String, Any>) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i("GeoJsonUtils", "Executing HTTP streaming download for: $url")
                val request = Request.Builder().url(url).build()
                var success = false
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            // Choose the parser by CONTENT, not the URL. The real
                            // airspace endpoint serves *_asp.geojson files that are
                            // standard FeatureCollections, while the test mock and some
                            // sources serve newline-delimited GeoJSON. Guessing from the
                            // URL (the old `url.contains("_asp.geojson")`) forced real
                            // FeatureCollections through the line parser, so every line
                            // failed and zero airspaces were ever cached. We sniff the
                            // first 1 KB and reuse the SAME detector PGSpotCache uses
                            // (isNdGeoJson), so both overlay caches auto-handle both
                            // formats identically. Unknown formats fall through to the
                            // FeatureCollection parser and, finding no features, return
                            // false — the caller clears the region and degrades to "no
                            // data for this country" rather than crashing.
                            val stream = java.io.BufferedInputStream(body.byteStream())
                            stream.mark(2048)
                            val head = ByteArray(1024)
                            val headLen = stream.read(head).coerceAtLeast(0)
                            stream.reset()
                            val headStr = String(head, 0, headLen, Charsets.UTF_8)
                            val isNd = isNdGeoJson(headStr)
                            Log.i("GeoJsonUtils", "format=${if (isNd) "NDGEOJSON" else "FEATURE_COLLECTION"} (auto-detected) for $url")
                            if (isNd) {
                                // NDGeoJSON: process line by line
                                stream.bufferedReader().useLines { lines ->
                                    for (line in lines) {
                                        if (!isActive) break
                                        if (line.isNotBlank()) {
                                            try {
                                                @Suppress("UNCHECKED_CAST")
                                                val feature = mapper.readValue<Map<String, Any>>(line, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {})
                                                processFeature(feature)
                                            } catch (e: Exception) {
                                                // Ignore malformed lines silently on streams
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Standard GeoJSON FeatureCollection
                                val parser = mapper.factory.createParser(stream)
                                var inFeaturesArray = false
                                while (!parser.isClosed && isActive) {
                                    val token = parser.nextToken()
                                    if (token == null) break

                                    if (!inFeaturesArray) {
                                        if (token == com.fasterxml.jackson.core.JsonToken.FIELD_NAME && parser.currentName == "features") {
                                            if (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
                                                inFeaturesArray = true
                                            }
                                        }
                                    } else {
                                        if (token == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                                            try {
                                                val feature: Map<String, Any> = mapper.readValue(parser, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {})
                                                processFeature(feature)
                                            } catch (e: Exception) {
                                                Log.w("GeoJsonUtils", "Failed to parse streamed feature object: ${e.message}")
                                            }
                                        } else if (token == com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                                            break
                                        }
                                    }
                                }
                                parser.close()
                            }
                            Log.d("GeoJsonUtils", "✅ Streaming download completed for $url")
                            success = true
                        } else {
                            Log.w("GeoJsonUtils", "Response body is null for $url")
                        }
                    } else {
                        Log.w("GeoJsonUtils", "Failed to download from $url: ${response.code} ${response.message}")
                    }
                }
                success
            } catch (e: Exception) {
                Log.w("GeoJsonUtils", "Exception streaming $url: ${e.message}")
                false
            }
        }
    }

    /**
     * Validate that downloaded content is valid GeoJSON
     */
    private fun validateGeoJsonContent(content: String, url: String): Boolean {
        if (content.isEmpty()) {
            Log.w("GeoJsonUtils", "Content is empty for $url")
            return false
        }

        // Check minimum size (empty or very small files are likely corrupted)
        if (content.length < 50) {
            Log.w("GeoJsonUtils", "Content too small (${content.length} bytes) for $url")
            return false
        }

        // Check for basic JSON structure
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            Log.w("GeoJsonUtils", "Content doesn't start with valid JSON structure for $url")
            return false
        }

        // For NDGeoJSON files (newline-delimited), check if it's properly formatted
        // Also handle _asp.geojson files which are NDGeoJSON
        if (url.contains("ndgeojson") || url.contains("_asp.geojson")) {
            return validateNdGeoJsonContent(content, url)
        }

        // For standard GeoJSON files, do basic JSON validation
        return try {
            // Try to parse as JSON to ensure it's not corrupted
            mapper.readTree(content)
            true
        } catch (e: Exception) {
            android.util.Log.w("GeoJsonUtils", "Invalid JSON structure for $url: ${e.message}")
            false
        }
    }

    /**
     * Check if content is likely NDGeoJSON (Newline Delimited GeoJSON)
     */
    fun isNdGeoJson(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        
        // If it starts with a JSON object '{' and has multiple lines, check if the first line is a Feature
        // Standard GeoJSON FeatureCollection also starts with '{', but usually the whole file is one JSON object
        // NDGeoJSON has multiple independent JSON objects, one per line
        
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) {
            // Single line: ambiguous. If it's a FeatureCollection, it's standard.
            // If it's a Feature, it could be either (but usually standard GeoJSON is FeatureCollection)
            // Let's assume single line starting with { "type": "FeatureCollection" ... } is Standard
            return !trimmed.contains("\"type\"\\s*:\\s*\"FeatureCollection\"".toRegex())
        }
        
        // Multiple lines: Check if first line is a valid JSON object (Feature)
        // and NOT a FeatureCollection start
        val firstLine = lines[0].trim()
        
        try {
            // Try to parse the first line as a JSON object
            val jsonNode = mapper.readTree(firstLine)
            
            // If it parses successfully, it's either a single-line standard GeoJSON or a line of NDGeoJSON
            // If it's a FeatureCollection, it's standard.
            if (jsonNode.has("type") && jsonNode.get("type").asText() == "FeatureCollection") {
                return false
            }
            
            // If it's a Feature (or other object) and on a single line (in a multi-line file), 
            // it's likely NDGeoJSON (where each line is a Feature).
            // However, if the file has only one line, it's ambiguous but we treat single Feature as NDGeoJSON-compatible.
            return true
            
        } catch (e: Exception) {
            // If the first line is NOT a valid JSON object (e.g. just "{"), 
            // it's likely a pretty-printed standard GeoJSON.
            return false
        }
    }

    /**
     * Validate NDGeoJSON (newline-delimited GeoJSON) content
     */
    private fun validateNdGeoJsonContent(content: String, url: String): Boolean {
        val lines = content.lines()

        if (lines.isEmpty()) {
            android.util.Log.w("GeoJsonUtils", "NDGeoJSON has no lines for $url")
            return false
        }

        var validLines = 0
        var invalidLines = 0

        // Check first few lines and some random samples
        val linesToCheck = minOf(50, lines.size) // Check up to 50 lines

        for (i in 0 until linesToCheck) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            if (isValidGeoJsonLine(line)) {
                validLines++
            } else {
                invalidLines++
            }
        }

        // Calculate validity ratio
        val totalChecked = validLines + invalidLines
        val validityRatio = if (totalChecked > 0) validLines.toDouble() / totalChecked else 0.0

        android.util.Log.d("GeoJsonUtils", "NDGeoJSON validation for $url: $validLines valid, $invalidLines invalid (${String.format("%.1f%%", validityRatio * 100)})")

        // Require at least 80% valid lines for acceptance
        return validityRatio >= 0.8
    }

    /**
     * Check if a single line is valid GeoJSON
     */
    private fun isValidGeoJsonLine(line: String): Boolean {
        if (line.isEmpty()) return false

        return try {
            // Should be a valid JSON object starting with "{"
            line.trim().startsWith("{") && mapper.readTree(line) != null
        } catch (e: Exception) {
            false
        }
    }
}
