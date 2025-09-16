@file:Suppress("unused")

package com.madanala.tern.model

import android.content.Context
import com.madanala.tern.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Request
import java.io.IOException

class AirspaceRepository(
    context: Context, // App context to get HttpClientProvider instance
    private val countryCode: String
) {
    // Get the shared OkHttpClient instance
    private val httpClient = HttpClientProvider.getInstance(context.applicationContext)
    private var airspaceFeatureCollection: AirspaceFeatureCollection? = null
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun loadAirspaces(forceRefresh: Boolean = false): AirspaceFeatureCollection? = withContext(Dispatchers.IO) {
        val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_asp.geojson"
        val requestBuilder = Request.Builder().url(url)

        if (forceRefresh) {
            requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
        }

        val request = requestBuilder.build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                println("Failed to get GeoJSON for $countryCode. HTTP Code: ${response.code}")
                return@withContext null
            }

            // Use response.body.string() which is non-null for successful responses
            val geoJsonString = response.body.string()
            
            airspaceFeatureCollection = jsonParser.decodeFromString(geoJsonString)
            println("Successfully parsed airspaces for $countryCode.")
            return@withContext airspaceFeatureCollection

        } catch (e: IOException) {
            println("IOException for $countryCode: ${e.message}")
            // In case of network error, return null
            return@withContext null
        } catch (e: kotlinx.serialization.SerializationException) {
            println("Failed to parse GeoJSON for $countryCode: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            println("Unexpected error parsing GeoJSON for $countryCode: ${e.message}")
            return@withContext null
        }
    }

    fun getLoadedAirspaceFeatures(): List<AirspaceFeature> {
        return airspaceFeatureCollection?.features ?: emptyList()
    }

    fun getLoadedAirspaceFeatureCollection(): AirspaceFeatureCollection? {
        return airspaceFeatureCollection
    }
}
