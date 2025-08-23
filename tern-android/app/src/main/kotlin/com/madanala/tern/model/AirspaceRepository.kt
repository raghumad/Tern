package com.madanala.tern.model

import android.content.Context
import com.madanala.tern.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class AirspaceRepository(
    context: Context, // App context to get HttpClientProvider instance
    private val countryCode: String
) {
    // Get the shared OkHttpClient instance
    private val httpClient: OkHttpClient = HttpClientProvider.getInstance(context.applicationContext)

    private var airspaceFeatureCollection: AirspaceFeatureCollection? = null
    private val jsonParser by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    suspend fun loadAirspaces(forceRefresh: Boolean = false): AirspaceFeatureCollection? = withContext(Dispatchers.IO) {
        val url = "https://storage.googleapis.com/29f98e10-a489-4c82-ae5e-489dbcd4912f/${countryCode}_asp.geojson"
        val requestBuilder = Request.Builder().url(url)

        if (forceRefresh) {
            // Ask OkHttp to bypass cache for this request and go directly to network
            requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
        } else {
            // Default behavior: OkHttp will use its cache if the response is cacheable.
            // If the server doesn't provide strong caching headers, OkHttp might still cache it
            // based on its default heuristics or if configured with an interceptor to force caching.
            // For simple GET requests to URLs like this, OkHttp is generally good at caching.
        }

        val request = requestBuilder.build()
        var geoJsonString: String? = null

        try {
            val response = httpClient.newCall(request).execute()
            val source = if (response.cacheResponse != null) "cache" else "network"
            
            if (!response.isSuccessful) {
                println("Failed to get GeoJSON for $countryCode from $source. HTTP Code: ${response.code}")
                response.close()
                return@withContext null
            }
            
            geoJsonString = response.body?.string()
            println("Successfully fetched GeoJSON for $countryCode from $source. Length: ${geoJsonString?.length}")
            response.close()

            if (geoJsonString == null) {
                println("GeoJSON body for $countryCode from $source was null or empty.")
                return@withContext null
            }

        } catch (e: IOException) {
            println("IOException for $countryCode: ${e.message}")
            // If offline and forceRefresh is false, OkHttp might have served a stale response if configured.
            // Otherwise, this is likely a network issue.
            throw e
        }

        try {
            airspaceFeatureCollection = jsonParser.decodeFromString<AirspaceFeatureCollection>(geoJsonString)
            println("Successfully parsed airspaces for $countryCode.")
            return@withContext airspaceFeatureCollection
        } catch (e: kotlinx.serialization.SerializationException) {
            println("Failed to parse GeoJSON for $countryCode: ${e.message}")
            throw e
        } catch (e: Exception) {
            println("Unexpected error parsing GeoJSON for $countryCode: ${e.message}")
            throw e
        }
        return@withContext null
    }

    fun getLoadedAirspaceFeatures(): List<AirspaceFeature> {
        return airspaceFeatureCollection?.features ?: emptyList()
    }

    fun getLoadedAirspaceFeatureCollection(): AirspaceFeatureCollection? {
        return airspaceFeatureCollection
    }
}
