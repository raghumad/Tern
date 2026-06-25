package com.ternparagliding.utils.io

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Terrain elevation from Open-Meteo's free elevation API (Copernicus ~90 m DEM). Gives
 * ad-hoc map-dropped waypoints a real ground elevation instead of the 0 m default, so the
 * soaring sounding (cloudbase / thermal top) can root its parcel ascent against terrain.
 */
object ElevationApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val mapper = jacksonObjectMapper()
    private const val MAX_PER_CALL = 100 // Open-Meteo elevation cap

    /** Elevation (m) for each input point, in order; null where unavailable. Batches to
     *  stay within the API's per-call coordinate cap. */
    suspend fun fetch(points: List<Pair<Double, Double>>): List<Double?> = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()
        points.chunked(MAX_PER_CALL).flatMap { chunk ->
            val lats = chunk.joinToString(",") { it.first.toString() }
            val lons = chunk.joinToString(",") { it.second.toString() }
            val url = "https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@flatMap arrayOfNulls<Double>(chunk.size).toList()
                    val body = resp.body?.string() ?: return@flatMap arrayOfNulls<Double>(chunk.size).toList()
                    @Suppress("UNCHECKED_CAST")
                    val map = mapper.readValue(body, Map::class.java) as Map<String, Any?>
                    val elev = map["elevation"] as? List<*>
                    chunk.indices.map { i -> (elev?.getOrNull(i) as? Number)?.toDouble()?.takeIf { it.isFinite() } }
                }
            } catch (e: Exception) {
                Log.w("ElevationApi", "elevation fetch failed: ${e.message}")
                arrayOfNulls<Double>(chunk.size).toList()
            }
        }
    }
}
