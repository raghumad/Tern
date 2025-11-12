@file:Suppress("unused")

package com.madanala.tern.network // Or your preferred package for network utilities

import android.content.Context
import androidx.preference.PreferenceManager // For easy SharedPreferences access
import com.madanala.tern.redux.CacheConstants
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

object HttpClientProvider {

    private const val DEFAULT_MAX_CACHE_SIZE_MB = 5120L // 5GB default
    private const val PREF_KEY_MAX_CACHE_SIZE_MB = "max_geojson_cache_size_mb"
    private const val CACHE_DIR_NAME = "geojson_http_cache" // Specific cache for these GeoJSON files

    @Volatile
    private var instance: OkHttpClient? = null

    fun getInstance(context: Context): OkHttpClient {
        return instance ?: synchronized(this) {
            instance ?: buildOkHttpClient(context.applicationContext).also { instance = it }
        }
    }

    private fun buildOkHttpClient(appContext: Context): OkHttpClient {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        val maxCacheSizeMB = sharedPreferences.getString(PREF_KEY_MAX_CACHE_SIZE_MB, DEFAULT_MAX_CACHE_SIZE_MB.toString())
            ?.toLongOrNull() ?: DEFAULT_MAX_CACHE_SIZE_MB
        val maxCacheSizeBytes = maxCacheSizeMB * CacheConstants.BYTES_PER_MB

        val cacheDirectory = File(appContext.cacheDir, CACHE_DIR_NAME)
        val cache = Cache(cacheDirectory, maxCacheSizeBytes)

        // Basic OkHttpClient builder. You can add interceptors, timeouts, etc.
        return OkHttpClient.Builder()
            .cache(cache)
            // Example: Add an interceptor to log cache hits/misses or modify requests
            // .addNetworkInterceptor(createLoggingInterceptor())
            .build()
    }

    // Call this if the cache size preference changes to re-initialize the client.
    // This is a simple way; a more sophisticated app might have a better way to update components.
    fun resetInstance() {
        synchronized(this) {
            instance = null
        }
    }
}
