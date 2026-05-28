package com.ternparagliding.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.utils.WeatherForecast
import com.ternparagliding.utils.MapVisualTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WeatherCacheTest : MapVisualTest() {

    private lateinit var weatherCache: WeatherCache
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun initCache() {
        CacheManager.initialize(context)
        weatherCache = CacheManager.weatherCache
        weatherCache.clearCache()
    }

    @Test
    fun testWeatherCachingAndRetrieval() {
        scenario("Caching and Retrieving Weather Forecasts") {
            val routeId = UUID.randomUUID().toString()
            val location = GeoPoint(45.0, 6.0) // France
            var forecast: WeatherForecast? = null
            var retrievedForecasts: List<WeatherForecast>? = null

            given("a weather forecast for a location") {
                forecast = WeatherForecast(
                    current = null,
                    daily = emptyList(),
                    hourly = emptyList()
                )
            }

            `when`("the forecast is cached") {
                weatherCache.cacheWeather(routeId, location, forecast!!)
            }

            and("we query for weather near that location") {
                retrievedForecasts = weatherCache.queryNearbyWeather(routeId, location, 10.0) // 10 miles radius
            }

            this.then("the cached forecast should be returned") {
                assertTrue(retrievedForecasts!!.isNotEmpty())
                val retrieved = retrievedForecasts!!.first()
                assertEquals(forecast!!.daily.size, retrieved.daily.size)
            }
        }
    }
}
