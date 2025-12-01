package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.madanala.tern.utils.WeatherForecast
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.osmdroid.util.GeoPoint
import java.io.File

class WeatherCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var weatherCache: WeatherCache

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0

        context = mockk<Context>()
        // WeatherCache uses "weather" -> "weather_cache"
        every { context.cacheDir } returns tempFolder.root
        cacheDir = File(tempFolder.root, "weather_cache")

        weatherCache = WeatherCache(context)
    }

    @After
    fun tearDown() {
        weatherCache.clearCache()
        unmockkStatic(Log::class)
    }

    @Test
    fun `cacheWeather stores forecast and queryNearbyWeather retrieves it`() {
        val id = "test_route"
        val location = GeoPoint(47.0, 8.0)
        
        // Create dummy forecast using utils.WeatherForecast
        val forecast = WeatherForecast(
            current = null,
            daily = emptyList(),
            hourly = emptyList()
        )

        weatherCache.cacheWeather(id, location, forecast)

        // Verify files exist
        val flexFile = File(cacheDir, "${id}_weather.flex")
        val idxFile = File(cacheDir, "${id}_weather.idx")
        
        assertTrue("Flex file should exist", flexFile.exists())
        assertTrue("Index file should exist", idxFile.exists())

        // Query nearby
        val results = weatherCache.queryNearbyWeather(id, location, 10.0)
        
        assertTrue("Should return cached forecast", results.isNotEmpty())
        assertEquals(1, results.size)
        // Since we stored empty lists, we expect empty lists back
        assertTrue(results[0].daily.isEmpty())
    }

    @Test
    fun `queryNearbyWeather returns empty for distant location`() {
        val id = "test_route_2"
        val location = GeoPoint(47.0, 8.0)
        val forecast = WeatherForecast(
            current = null,
            daily = emptyList(),
            hourly = emptyList()
        )

        weatherCache.cacheWeather(id, location, forecast)

        // Query far away (100 miles away)
        val distantLocation = GeoPoint(48.0, 9.0) // ~100km+ away
        val results = weatherCache.queryNearbyWeather(id, distantLocation, 10.0)
        
        assertTrue("Should return empty list for distant location", results.isEmpty())
    }
}
