package com.madanala.tern.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.model.CapePoint
import com.madanala.tern.model.SkewTForecast
import com.madanala.tern.model.WeatherForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WeatherCacheTest : BddTest() {

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
                    skewT = SkewTForecast(emptyList()),
                    cape = listOf(CapePoint("12:00", 500.0)),
                    wind = emptyList()
                )
            }

            `when`("the forecast is cached") {
                weatherCache.cacheWeather(routeId, location, forecast!!)
            }

            and("we query for weather near that location") {
                retrievedForecasts = weatherCache.queryNearbyWeather(routeId, location, 10.0) // 10 miles radius
            }

            then("the cached forecast should be returned") {
                assertTrue(retrievedForecasts!!.isNotEmpty())
                val retrieved = retrievedForecasts!!.first()
                assertEquals(forecast!!.cape.size, retrieved.cape.size)
                assertEquals(forecast!!.cape[0].cape, retrieved.cape[0].cape, 0.1)
            }
        }
    }
}
