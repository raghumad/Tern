package com.madanala.tern

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.net.URL
import java.io.FileOutputStream
import java.io.IOException

class AirspaceService(private val context: Context) {
    
    companion object {
        private const val TAG = "AirspaceService"
        private const val BASE_URL = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f"
        private const val CACHE_DIR = "TernAirspaceCache"
    }
    
    private val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    suspend fun loadAirspaces(countryCode: String): List<Airspace> = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(cacheDir, "${countryCode}_asp.geojson")
            
            // Download if not cached
            if (!cacheFile.exists()) {
                downloadAirspaceData(countryCode, cacheFile)
            }
            
            // Parse cached data
            if (cacheFile.exists()) {
                parseAirspaceData(cacheFile)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading airspaces for $countryCode", e)
            emptyList()
        }
    }
    
    private suspend fun downloadAirspaceData(countryCode: String, cacheFile: File) {
        try {
            val url = URL("$BASE_URL/${countryCode}_asp.geojson")
            Log.d(TAG, "Downloading airspace data from: $url")
            
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            connection.getInputStream().use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "Airspace data downloaded successfully to: ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading airspace data", e)
            throw e
        }
    }
    
    private fun parseAirspaceData(cacheFile: File): List<Airspace> {
        val airspaces = mutableListOf<Airspace>()
        
        try {
            val jsonString = cacheFile.readText()
            val jsonObject = JSONObject(jsonString)
            val features = jsonObject.getJSONArray("features")
            
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val properties = feature.getJSONObject("properties")
                val geometry = feature.getJSONObject("geometry")
                
                val airspace = Airspace(
                    id = properties.optString("id", "unknown"),
                    name = properties.optString("name", "Unknown Airspace"),
                    type = properties.optString("type", "unknown"),
                    coordinates = parseCoordinates(geometry),
                    properties = properties.toMap()
                )
                
                airspaces.add(airspace)
            }
            
            Log.d(TAG, "Parsed ${airspaces.size} airspaces")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing airspace data", e)
        }
        
        return airspaces
    }
    
    private fun parseCoordinates(geometry: JSONObject): List<GeoPoint> {
        val coordinates = mutableListOf<GeoPoint>()
        
        try {
            val coordsArray = geometry.getJSONArray("coordinates")
            if (geometry.getString("type") == "Polygon") {
                val polygonCoords = coordsArray.getJSONArray(0) // First ring of polygon
                for (i in 0 until polygonCoords.length()) {
                    val coord = polygonCoords.getJSONArray(i)
                    val lng = coord.getDouble(0)
                    val lat = coord.getDouble(1)
                    coordinates.add(GeoPoint(lat, lng))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing coordinates", e)
        }
        
        return coordinates
    }
    
    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = this.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = this.get(key)
        }
        return map
    }
    
    fun createAirspaceOverlays(airspaces: List<Airspace>): List<Polygon> {
        return airspaces.map { airspace ->
            val polygon = Polygon().apply {
                points = airspace.coordinates
                fillPaint.color = getAirspaceColor(airspace.type)
                outlinePaint.color = getAirspaceOutlineColor(airspace.type)
                outlinePaint.strokeWidth = 2f
                title = airspace.name
            }
            polygon
        }
    }
    
    private fun getAirspaceColor(type: String): Int {
        return when (type.lowercase()) {
            "restricted" -> android.graphics.Color.RED
            "prohibited" -> android.graphics.Color.RED
            "controlled" -> android.graphics.Color.YELLOW
            "danger" -> android.graphics.Color.MAGENTA
            "warning" -> android.graphics.Color.rgb(255, 165, 0) // Orange
            else -> android.graphics.Color.rgb(128, 128, 128) // Gray
        }
    }
    
    private fun getAirspaceOutlineColor(type: String): Int {
        return when (type.lowercase()) {
            "restricted", "prohibited" -> android.graphics.Color.rgb(139, 0, 0) // Dark Red
            "controlled" -> android.graphics.Color.rgb(139, 69, 19) // Brown
            "danger" -> android.graphics.Color.rgb(139, 0, 139) // Dark Magenta
            "warning" -> android.graphics.Color.rgb(139, 69, 0) // Dark Orange
            else -> android.graphics.Color.rgb(64, 64, 64) // Dark Gray
        }
    }
    
    fun cleanup() {
        scope.cancel()
    }
}
