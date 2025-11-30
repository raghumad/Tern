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
    /**
     * Generate a QR Code Bitmap from a Route (XCTSK Compressed)
     */
    fun generateQRCode(route: Route): Bitmap? {
        val routeJson = generateXctskCompressed(route)
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
            importRouteFromQrString(result.text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Import a Route from a QR Code JSON String (XCTSK Compressed or Legacy)
     */
    fun importRouteFromQrString(jsonString: String): Route? {
        return if (jsonString.contains("\"z\":")) {
            parseXctskCompressed(jsonString)
        } else {
            deserializeRouteFromJson(jsonString)
        }
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

    // Legacy internal JSON (keep for backward compatibility if needed, or replace usage)
    private fun serializeRouteToJson(route: Route): String {
        // ... (existing implementation)
        return generateXctskCompressed(route) // Use XCTSK compressed for everything now?
        // No, let's keep legacy for now if we want, but generateQRCode now calls generateXctskCompressed directly.
        // So this method might be unused or can be deprecated.
        // For now, I'll leave the existing implementation in the file (it's not in the replacement chunk)
        // or I can update it to use the new fields if we still use it.
        // The prompt asked to update it.
        val json = JSONObject()
        json.put("n", route.name)
        val waypointsArray = org.json.JSONArray()
        route.waypoints.forEach { wp ->
            val wpJson = JSONObject()
            wpJson.put("lat", wp.lat)
            wpJson.put("lon", wp.lon)
            wpJson.put("t", wp.type.name)
            if (!wp.label.isNullOrEmpty()) wpJson.put("l", wp.label)
            wpJson.put("r", wp.radius)
            if (wp.alt != null) wpJson.put("a", wp.alt)
            if (wp.openTime != null) wpJson.put("o", wp.openTime)
            if (wp.closeTime != null) wpJson.put("c", wp.closeTime)
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
                val label = wpJson.optString("l").ifEmpty { null }
                val radius = wpJson.optDouble("r", 400.0)
                val alt = if (wpJson.has("a")) wpJson.getDouble("a") else null
                val openTime = wpJson.optString("o").ifEmpty { null }
                val closeTime = wpJson.optString("c").ifEmpty { null }
                
                waypoints.add(Waypoint(
                    lat = lat,
                    lon = lon,
                    type = type,
                    label = label,
                    radius = radius,
                    alt = alt,
                    openTime = openTime,
                    closeTime = closeTime
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

    fun generateXctskContent(route: Route): String {
        // Verbose XCTSK (XCTrack Task)
        val json = JSONObject()
        json.put("taskType", "CLASSIC")
        json.put("version", 1) // Using version 1 as per iOS saveXCTSK
        json.put("earthModel", "WGS84")
        
        val turnpoints = org.json.JSONArray()
        route.waypoints.forEachIndexed { index, wp ->
            val tp = JSONObject()
            // Map Type
            tp.put("type", when(wp.type) {
                Waypoint.Type.LAUNCH -> "TAKEOFF"
                Waypoint.Type.SSS -> "SSS"
                Waypoint.Type.ESS -> "ESS"
                Waypoint.Type.GOAL -> "GOAL"
                else -> "TURNPOINT" // Default
            })
            
            tp.put("radius", wp.radius ?: 400.0)
            
            val wpObj = JSONObject()
            wpObj.put("name", wp.label ?: "WP${index+1}")
            wpObj.put("description", "")
            wpObj.put("lat", wp.lat)
            wpObj.put("lon", wp.lon)
            if (wp.alt != null) {
                wpObj.put("altSmoothed", wp.alt)
            }
            tp.put("waypoint", wpObj)
            
            // Time Gates (Start)
        if (wp.type == Waypoint.Type.SSS && wp.openTime != null) {
             val sssObj = JSONObject()
             sssObj.put("type", "RACE") // Default to RACE
             sssObj.put("direction", "EXIT") // Default
             val gates = org.json.JSONArray()
             // Append :00Z if not present (assuming internal format is HH:mm)
             gates.put("${wp.openTime}:00Z")
             sssObj.put("gates", gates)
             tp.put("sss", sssObj)
        }
            
            turnpoints.put(tp)
        }
        json.put("turnpoints", turnpoints)
        
        // Goal Deadline
        val goalWp = route.waypoints.find { it.type == Waypoint.Type.GOAL }
        if (goalWp?.closeTime != null) {
            val goalObj = JSONObject()
            goalObj.put("type", "CYLINDER")
            goalObj.put("deadline", "${goalWp.closeTime}:00Z") // Simple formatting
            json.put("goal", goalObj)
        } else {
             val goalObj = JSONObject()
             goalObj.put("type", "CYLINDER")
             goalObj.put("deadline", "23:59:59Z")
             json.put("goal", goalObj)
        }

        return json.toString()
    }

    fun parseXctskContent(content: String): Route? {
        return try {
            val json = JSONObject(content)
            val turnpoints = json.getJSONArray("turnpoints")
            val waypoints = mutableListOf<Waypoint>()
            
            for (i in 0 until turnpoints.length()) {
                val tp = turnpoints.getJSONObject(i)
                val typeStr = tp.optString("type", "TURNPOINT")
                val radius = tp.optDouble("radius", 400.0)
                
                val wpObj = tp.getJSONObject("waypoint")
                val lat = wpObj.getDouble("lat")
                val lon = wpObj.getDouble("lon")
                val name = wpObj.optString("name", "WP$i")
                val alt = if (wpObj.has("altSmoothed")) wpObj.getDouble("altSmoothed") else null
                
                var type = when(typeStr) {
                    "TAKEOFF" -> Waypoint.Type.LAUNCH
                    "SSS" -> Waypoint.Type.SSS
                    "ESS" -> Waypoint.Type.ESS
                    "GOAL" -> Waypoint.Type.GOAL
                    else -> Waypoint.Type.TURNPOINT
                }
                
                var openTime: String? = null
            val sssObj = tp.optJSONObject("sss")
            if (sssObj != null) {
                val gates = sssObj.optJSONArray("gates")
                if (gates != null && gates.length() > 0) {
                    val firstGate = gates.getString(0)
                    // Parse HH:mm from HH:mm:ssZ or HH:mm:ss
                    if (firstGate.length >= 5) {
                        openTime = firstGate.substring(0, 5)
                    }
                }
            }
            
            waypoints.add(Waypoint(
                lat = lat, 
                lon = lon, 
                type = type, 
                label = name, 
                radius = radius,
                alt = alt,
                openTime = openTime
            ))
            }
            
            // Goal deadline
            val goalJson = json.optJSONObject("goal")
            if (goalJson != null) {
                val deadline = goalJson.optString("deadline")
                if (deadline.isNotEmpty()) {
                    val goalWp = waypoints.find { it.type == Waypoint.Type.GOAL }
                    if (goalWp != null) {
                        // Parse HH:mm from HH:mm:ssZ
                        val time = deadline.substring(0, 5)
                        // We need to replace the waypoint in the list (immutable data class)
                        val index = waypoints.indexOf(goalWp)
                        waypoints[index] = goalWp.copy(closeTime = time)
                    }
                }
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

    private fun generateXctskCompressed(route: Route): String {
        // XCTSK Compressed (QR Code) - Matches iOS saveXCTSKqr
        val json = JSONObject()
        json.put("taskType", "CLASSIC")
        json.put("version", 2)
        
        val tArray = org.json.JSONArray()
        route.waypoints.forEachIndexed { index, wp ->
            val tp = JSONObject()
            
            // Encode Polyline: lat, lon, alt, radius
            // iOS: encpoly.append(polyline.encodedPolyline)
            //      encpoly.append(encodeSingleInteger(alt))
            //      encpoly.append(encodeSingleInteger(radius))
            
            val poly = PolylineUtils.encodePolyline(listOf(wp))
            val altEnc = PolylineUtils.encodeSingleInteger(wp.alt?.toInt() ?: 0)
            val radEnc = PolylineUtils.encodeSingleInteger(wp.radius?.toInt() ?: 400)
            
            tp.put("z", "$poly$altEnc$radEnc")
            tp.put("n", wp.label ?: "WP${index+1}")
            tp.put("d", "") // Description
            
            // Type mapping for compressed (numeric)
            // 2=SSS, 3=ESS. Others implicit?
            if (wp.type == Waypoint.Type.SSS) tp.put("t", 2)
            if (wp.type == Waypoint.Type.ESS) tp.put("t", 3)
            
            tArray.put(tp)
        }
        json.put("t", tArray)
        json.put("e", 0) // Earth model WGS84
        
        // SSS Time Gates
        val sssWp = route.waypoints.find { it.type == Waypoint.Type.SSS }
        if (sssWp?.openTime != null) {
            val sObj = JSONObject()
            val gArray = org.json.JSONArray()
            gArray.put("${sssWp.openTime}:00") // HH:mm:ss
            sObj.put("g", gArray)
            sObj.put("t", 1) // RACE
            json.put("s", sObj)
        }
        
        // Goal Deadline
        val goalWp = route.waypoints.find { it.type == Waypoint.Type.GOAL }
        if (goalWp?.closeTime != null) {
            val gObj = JSONObject()
            gObj.put("d", "${goalWp.closeTime}:00")
            gObj.put("t", 2) // CYLINDER
            json.put("g", gObj)
        }

        return json.toString()
    }

    private fun parseXctskCompressed(jsonString: String): Route? {
        return try {
            val json = JSONObject(jsonString)
            val tArray = json.getJSONArray("t")
            val waypoints = mutableListOf<Waypoint>()
            
            for (i in 0 until tArray.length()) {
                val tp = tArray.getJSONObject(i)
                val z = tp.getString("z")
                val name = tp.optString("n", "WP$i")
                val typeCode = tp.optInt("t", 0)
                
                // Decode Z string
                // It contains: Polyline(lat,lon) + Encoded(Alt) + Encoded(Radius)
                val values = PolylineUtils.decodeValues(z)
                
                val lat = if (values.isNotEmpty()) values[0] / 1e5 else 0.0
                val lon = if (values.size > 1) values[1] / 1e5 else 0.0
                val alt = if (values.size > 2) values[2].toDouble() else 0.0
                val radius = if (values.size > 3) values[3].toDouble() else 400.0
                
                var type = when(typeCode) {
                    2 -> Waypoint.Type.SSS
                    3 -> Waypoint.Type.ESS
                    else -> if (i == 0) Waypoint.Type.LAUNCH else if (i == tArray.length()-1) Waypoint.Type.GOAL else Waypoint.Type.TURNPOINT
                }
                // Refine type based on position if not explicit
                
                waypoints.add(Waypoint(lat=lat, lon=lon, type=type, label=name, radius=radius, alt=alt))
            }
            
            // Parse SSS Gates
            val sObj = json.optJSONObject("s")
            if (sObj != null) {
                val gArray = sObj.optJSONArray("g")
                if (gArray != null && gArray.length() > 0) {
                    val time = gArray.getString(0).substring(0, 5)
                    val sssWp = waypoints.find { it.type == Waypoint.Type.SSS }
                    if (sssWp != null) {
                        val index = waypoints.indexOf(sssWp)
                        waypoints[index] = sssWp.copy(openTime = time)
                    }
                }
            }
            
            // Parse Goal Deadline
            val gObj = json.optJSONObject("g")
            if (gObj != null) {
                val deadline = gObj.optString("d")
                if (deadline.isNotEmpty()) {
                    val time = deadline.substring(0, 5)
                    val goalWp = waypoints.find { it.type == Waypoint.Type.GOAL }
                    if (goalWp != null) {
                        val index = waypoints.indexOf(goalWp)
                        waypoints[index] = goalWp.copy(closeTime = time)
                    }
                }
            }

            Route(id = UUID.randomUUID().toString(), name = "Imported Task", waypoints = waypoints)
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
        val waypoints = mutableListOf<Waypoint>()
        val lines = content.lines()
        
        for (line in lines) {
            if (line.isBlank() || line.startsWith("name,code", ignoreCase = true)) continue
            
            try {
                // Handle quoted strings correctly (basic regex split)
                // This regex matches commas that are NOT inside quotes
                val parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                if (parts.size < 5) continue
                
                val name = parts[0].replace("\"", "").trim()
                // parts[1] is Code, parts[2] is Country
                val latStr = parts[3].trim()
                val lonStr = parts[4].trim()
                val elevStr = parts.getOrNull(5)?.trim()
                val styleStr = parts.getOrNull(6)?.trim()
                
                val lat = parseCupCoord(latStr)
                val lon = parseCupCoord(lonStr)
                val alt = elevStr?.replace("m", "")?.replace("ft", "")?.toDoubleOrNull()
                
                // Style mapping (1=Waypoint, 2=Airfield, 3=Outlanding, 4=Gliding Site, 5=Turnpoint, etc.)
                val type = when(styleStr) {
                    "2", "3", "4", "5" -> Waypoint.Type.TURNPOINT // Treat most as turnpoints for now
                    else -> Waypoint.Type.TURNPOINT
                }
                
                if (lat != null && lon != null) {
                    waypoints.add(Waypoint(
                        lat = lat,
                        lon = lon,
                        type = type,
                        label = name,
                        alt = alt,
                        radius = 400.0 // Default radius
                    ))
                }
            } catch (e: Exception) {
                // Log error but continue parsing other lines
                e.printStackTrace()
            }
        }
        
        if (waypoints.isEmpty()) return null
        
        return Route(
            id = UUID.randomUUID().toString(),
            name = "Imported CUP Route",
            waypoints = waypoints,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    private fun parseCupCoord(coord: String): Double? {
        // Format: DDMM.mmmH or DDDMM.mmmH (e.g. 4621.167N or 00621.167E)
        try {
            if (coord.isEmpty()) return null
            val lastChar = coord.last().uppercaseChar()
            val isSouthOrWest = lastChar == 'S' || lastChar == 'W'
            
            // Remove suffix and any whitespace
            val valueStr = coord.dropLast(1).trim()
            
            // Find decimal point
            val dotIndex = valueStr.indexOf('.')
            if (dotIndex == -1) return null
            
            // Minutes is always 2 digits before dot + decimal part
            // e.g. in 4621.167, minutes is 21.167, degrees is 46
            val minutesStartIndex = dotIndex - 2
            if (minutesStartIndex < 0) return null
            
            val degreesStr = valueStr.substring(0, minutesStartIndex)
            val minutesStr = valueStr.substring(minutesStartIndex)
            
            val degrees = degreesStr.toDouble()
            val minutes = minutesStr.toDouble()
            
            val decimalDegrees = degrees + (minutes / 60.0)
            return if (isSouthOrWest) -decimalDegrees else decimalDegrees
        } catch (e: Exception) {
            return null
        }
    }
}
