package com.madanala.tern.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.time.Instant

object RouteIOManager {

    private const val QR_SIZE = 512

    /**
     * Generate a QR Code Bitmap from a Route
     */
    fun generateQRCode(route: Route): Bitmap? {
        val routeJson = serializeRouteToJson(route)
        return try {
            val bitMatrix = MultiFormatWriter().encode(
                routeJson,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decode a Route from a QR Code Bitmap
     */
    fun decodeQRCode(bitmap: Bitmap): Route? {
        return try {
            val intArray = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBitmap)
            deserializeRouteFromJson(result.text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Import a Route from a QR Code JSON String
     */
    fun importRouteFromQrString(jsonString: String): Route? {
        return deserializeRouteFromJson(jsonString)
    }

    /**
     * Share a Route as a file (XCTSK or CUP)
     */
    fun shareRouteFile(context: Context, route: Route, format: String = "xctsk") {
        val fileName = "${route.name.replace(" ", "_")}.$format"
        val file = File(context.cacheDir, fileName)
        
        try {
            FileOutputStream(file).use { fos ->
                val content = when (format) {
                    "xctsk" -> generateXctskContent(route)
                    "cup" -> generateCupContent(route)
                    else -> ""
                }
                fos.write(content.toByteArray())
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain" // Or specific mime type
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Route"))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Import a Route from a URI
     */
    fun importRouteFromUri(context: Context, uri: Uri): Route? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Simple detection based on file extension or content could be added
                // For now, try to parse as JSON (XCTSK) first, then CUP
                val content = inputStream.bufferedReader().use { it.readText() }
                if (content.trim().startsWith("{")) {
                    parseXctskContent(content)
                } else {
                    parseCupContent(content)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- Helper Methods for Serialization/Deserialization ---

    private fun serializeRouteToJson(route: Route): String {
        // Simple JSON serialization for QR code
        // Format: { "n": "Name", "w": [ { "lat": 1.0, "lon": 2.0, "t": "TURNPOINT" } ] }
        // Minified to save space in QR
        val json = JSONObject()
        json.put("n", route.name)
        val waypointsArray = org.json.JSONArray()
        route.waypoints.forEach { wp ->
            val wpJson = JSONObject()
            wpJson.put("lat", wp.lat)
            wpJson.put("lon", wp.lon)
            wpJson.put("t", wp.type.name)
            // Optional label
            if (!wp.label.isNullOrEmpty()) wpJson.put("l", wp.label)
            waypointsArray.put(wpJson)
        }
        json.put("w", waypointsArray)
        return json.toString()
    }

    private fun deserializeRouteFromJson(jsonString: String): Route? {
        return try {
            val json = JSONObject(jsonString)
            val name = json.optString("n", "Imported Route")
            val waypointsArray = json.getJSONArray("w")
            val waypoints = mutableListOf<Waypoint>()
            
            for (i in 0 until waypointsArray.length()) {
                val wpJson = waypointsArray.getJSONObject(i)
                val lat = wpJson.getDouble("lat")
                val lon = wpJson.getDouble("lon")
                val typeStr = wpJson.optString("t", "TURNPOINT")
                val type = try { Waypoint.Type.valueOf(typeStr) } catch (e: Exception) { Waypoint.Type.TURNPOINT }
                val label = wpJson.optString("l", null)
                
                waypoints.add(Waypoint(
                    lat = lat,
                    lon = lon,
                    type = type,
                    label = label
                ))
            }
            
            Route(
                id = UUID.randomUUID().toString(),
                name = name,
                waypoints = waypoints,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun generateXctskContent(route: Route): String {
        // Basic XCTSK generation (XCTrack Task)
        // This is a simplified version. Real XCTSK is more complex.
        val json = JSONObject()
        json.put("taskType", "CLASSIC")
        json.put("version", 2)
        val turnpoints = org.json.JSONArray()
        
        route.waypoints.forEach { wp ->
            val tp = JSONObject()
            tp.put("lat", wp.lat)
            tp.put("lon", wp.lon)
            tp.put("radius", 400) // Default radius
            tp.put("type", when(wp.type) {
                Waypoint.Type.LAUNCH -> "TAKEOFF"
                Waypoint.Type.LANDING -> "GOAL"
                else -> "TURNPOINT"
            })
            turnpoints.put(tp)
        }
        json.put("turnpoints", turnpoints)
        return json.toString()
    }

    private fun parseXctskContent(content: String): Route? {
        // Basic XCTSK parsing
        return try {
            val json = JSONObject(content)
            val turnpoints = json.getJSONArray("turnpoints")
            val waypoints = mutableListOf<Waypoint>()
            
            for (i in 0 until turnpoints.length()) {
                val tp = turnpoints.getJSONObject(i)
                val lat = tp.getDouble("lat")
                val lon = tp.getDouble("lon")
                // Map XCTSK types to internal types if needed
                waypoints.add(Waypoint(lat = lat, lon = lon))
            }
            
            Route(
                id = UUID.randomUUID().toString(),
                name = "Imported XCTSK",
                waypoints = waypoints
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun generateCupContent(route: Route): String {
        // Basic CUP generation (Comma Separated)
        // Name,Code,Country,Lat,Lon,Elev,Style,RwDir,RwLen,Freq,Desc
        val sb = StringBuilder()
        sb.append("name,code,country,lat,lon,elev,style,rwdir,rwlen,freq,desc\n")
        route.waypoints.forEachIndexed { index, wp ->
            val latStr = convertToCupCoord(wp.lat, true)
            val lonStr = convertToCupCoord(wp.lon, false)
            sb.append("\"${wp.label ?: "WP${index+1}"}\",,,$latStr,$lonStr,,1,,,,\n")
        }
        return sb.toString()
    }
    
    private fun convertToCupCoord(coord: Double, isLat: Boolean): String {
        // CUP format: 1234.567N
        val absCoord = Math.abs(coord)
        val degrees = absCoord.toInt()
        val minutes = (absCoord - degrees) * 60
        val suffix = if (isLat) {
            if (coord >= 0) "N" else "S"
        } else {
            if (coord >= 0) "E" else "W"
        }
        return String.format("%02d%06.3f%s", degrees, minutes, suffix)
    }

    private fun parseCupContent(content: String): Route? {
        // Basic CUP parsing
        // TODO: Implement robust CUP parsing
        return null 
    }
}
