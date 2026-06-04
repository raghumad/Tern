package com.ternparagliding.utils
import com.ternparagliding.utils.cache.WeatherCache
import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WindData

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherInterpolationTest {

    @Test
    fun testLinearInterpolation_Midpoint() {
        val startWind = WindData(speed = 10.0, direction = 180.0, gust = 15.0)
        val endWind = WindData(speed = 20.0, direction = 180.0, gust = 25.0)
        
        val startWeather = WeatherData(startWind, 20.0, 50.0, 10.0, 1010.0, 10.0, 1000L)
        val endWeather = WeatherData(endWind, 30.0, 60.0, 20.0, 1020.0, 20.0, 2000L)
        
        val startPeriod = ForecastPeriod(1000L, 2000L, startWeather, "")
        val endPeriod = ForecastPeriod(2000L, 3000L, endWeather, "")
        
        // Midpoint (ratio 0.5)
        val targetTime = 1500L
        
        val result = WeatherCache.interpolateWeather(startPeriod, endPeriod, targetTime)
        
        assertEquals(15.0, result.wind.speed, 0.01)
        assertEquals(20.0, result.wind.gust, 0.01)
        assertEquals(25.0, result.temperature, 0.01)
        assertEquals(55.0, result.humidity, 0.01)
        assertEquals(15.0, result.visibility, 0.01)
        assertEquals(1015.0, result.pressure, 0.01)
        assertEquals(15.0, result.cloudCover, 0.01)
    }

    @Test
    fun testCircularInterpolation_WindDirection_Forward() {
        // From 350 to 10 (should go through 0/360, midpoint is 0)
        val startWind = WindData(speed = 10.0, direction = 350.0, gust = 10.0)
        val endWind = WindData(speed = 10.0, direction = 10.0, gust = 10.0)
        
        val startWeather = WeatherData(startWind, 20.0, 50.0, 10.0, 1010.0, 10.0, 1000L)
        val endWeather = WeatherData(endWind, 20.0, 50.0, 10.0, 1010.0, 10.0, 2000L)
        
        val startPeriod = ForecastPeriod(1000L, 2000L, startWeather, "")
        val endPeriod = ForecastPeriod(2000L, 3000L, endWeather, "")
        
        val resultMid = WeatherCache.interpolateWeather(startPeriod, endPeriod, 1500L)
        assertEquals(0.0, resultMid.wind.direction, 0.01)
        
        val resultQuarter = WeatherCache.interpolateWeather(startPeriod, endPeriod, 1250L)
        assertEquals(355.0, resultQuarter.wind.direction, 0.01)
        
        val resultThreeQuarter = WeatherCache.interpolateWeather(startPeriod, endPeriod, 1750L)
        assertEquals(5.0, resultThreeQuarter.wind.direction, 0.01)
    }
    
    @Test
    fun testCircularInterpolation_WindDirection_Backward() {
        // From 10 to 350 (should go backwards through 0/360, midpoint is 0)
        val startWind = WindData(speed = 10.0, direction = 10.0, gust = 10.0)
        val endWind = WindData(speed = 10.0, direction = 350.0, gust = 10.0)
        
        val startWeather = WeatherData(startWind, 20.0, 50.0, 10.0, 1010.0, 10.0, 1000L)
        val endWeather = WeatherData(endWind, 20.0, 50.0, 10.0, 1010.0, 10.0, 2000L)
        
        val startPeriod = ForecastPeriod(1000L, 2000L, startWeather, "")
        val endPeriod = ForecastPeriod(2000L, 3000L, endWeather, "")
        
        val resultMid = WeatherCache.interpolateWeather(startPeriod, endPeriod, 1500L)
        assertEquals(0.0, resultMid.wind.direction, 0.01)
    }

    @Test
    fun testCircularInterpolation_WindDirection_LargeGap() {
        // From 90 to 270 (difference is 180, could go either way, standard is positive or negative. Let's say it goes through 180)
        val startWind = WindData(speed = 10.0, direction = 90.0, gust = 10.0)
        val endWind = WindData(speed = 10.0, direction = 270.0, gust = 10.0)
        
        val startWeather = WeatherData(startWind, 20.0, 50.0, 10.0, 1010.0, 10.0, 1000L)
        val endWeather = WeatherData(endWind, 20.0, 50.0, 10.0, 1010.0, 10.0, 2000L)
        
        val startPeriod = ForecastPeriod(1000L, 2000L, startWeather, "")
        val endPeriod = ForecastPeriod(2000L, 3000L, endWeather, "")
        
        val resultMid = WeatherCache.interpolateWeather(startPeriod, endPeriod, 1500L)
        assertEquals(180.0, resultMid.wind.direction, 0.01)
    }
}
