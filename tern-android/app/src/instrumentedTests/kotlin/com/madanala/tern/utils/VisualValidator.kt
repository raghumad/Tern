package com.madanala.tern.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * Utility for automated screenshot validation.
 * Detects blank screens and provides comparison logic for Golden References.
 */
object VisualValidator {
    private const val TAG = "VisualValidator"
    private const val BLACKLIST_PATH = "goldens/blacklist.json"
    
    private var blacklist: Map<String, List<String>>? = null
    private var initialized = false

    /**
     * Detects if a screenshot is "Blank" (95% or more of the same color).
     * This is a robust way to detect rendering failures in tests.
     */
    fun isBlank(bitmap: Bitmap, threshold: Float = 0.95f): Boolean {
        if (bitmap.width == 0 || bitmap.height == 0) return true

        val totalPixels = bitmap.width * bitmap.height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val colorCounts = mutableMapOf<Int, Int>()
        for (pixel in pixels) {
            colorCounts[pixel] = (colorCounts[pixel] ?: 0) + 1
        }

        val maxCount = colorCounts.values.maxOrNull() ?: 0
        val blankness = maxCount.toFloat() / totalPixels

        Log.d(TAG, "Blankness score: $blankness (Threshold: $threshold)")
        return blankness >= threshold
    }

    /**
     * Compares two bitmaps pixel-by-pixel with a tolerance.
     * Returns a percentage of similarity (0.0 to 1.0).
     */
    fun getSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): Float {
        if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
            return 0.0f
        }

        val totalPixels = bitmap1.width * bitmap1.height
        val pixels1 = IntArray(totalPixels)
        val pixels2 = IntArray(totalPixels)

        bitmap1.getPixels(pixels1, 0, bitmap1.width, 0, 0, bitmap1.width, bitmap1.height)
        bitmap2.getPixels(pixels2, 0, bitmap2.width, 0, 0, bitmap2.width, bitmap2.height)

        var matchingPixels = 0
        for (i in 0 until totalPixels) {
            if (pixels1[i] == pixels2[i]) {
                matchingPixels++
            }
        }

        return matchingPixels.toFloat() / totalPixels
    }

    /**
     * Checks if a bitmap matches a previously rejected (blacklisted) state for this test.
     */
    fun isBlacklisted(testName: String, bitmap: Bitmap): Boolean {
        ensureInitialized()
        
        val currentHash = calculateHash(bitmap)
        val blacklistedHashes = blacklist?.get(testName) ?: return false
        
        return blacklistedHashes.contains(currentHash)
    }

    private fun ensureInitialized() {
        if (initialized) return
        
        try {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
            val assetManager = context.assets
            
            // Check if blacklist exists in assets
            val files = assetManager.list("goldens") ?: emptyArray()
            if (files.contains("blacklist.json")) {
                assetManager.open(BLACKLIST_PATH).use { input ->
                    val json = input.bufferedReader().readText()
                    // Simple JSON parsing since we don't want to add big dependencies to test utils
                    // Format: { "testName": ["hash1", "hash2"] }
                    blacklist = parseBlacklist(json)
                    Log.d(TAG, "Loaded blacklist with ${blacklist?.size} tests")
                }
            } else {
                Log.d(TAG, "No blacklist.json found in assets/goldens")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blacklist", e)
        } finally {
            initialized = true
        }
    }

    private fun parseBlacklist(json: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        try {
            // Manual parsing to avoid GSON/Jackson dependency in simple test utils
            // We use the basic org.json which is available in Android
            val jsonObject = org.json.JSONObject(json)
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = jsonObject.getJSONArray(key)
                val hashes = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    hashes.add(array.getString(i))
                }
                result[key] = hashes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing blacklist JSON", e)
        }
        return result
    }

    fun calculateHash(bitmap: Bitmap): String {
        val totalPixels = bitmap.width * bitmap.height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Simple but effective pixel-based hash
        var hash = 0L
        for (pixel in pixels) {
            hash = 31 * hash + pixel
        }
        return hash.toString(16)
    }
}
