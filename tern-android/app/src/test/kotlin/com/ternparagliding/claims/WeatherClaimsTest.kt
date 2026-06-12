package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.overlay.hazard.HazardLevel
import com.ternparagliding.overlay.hazard.classifyHazard
import com.ternparagliding.utils.cache.WeatherCache
import com.ternparagliding.utils.geo.CountryUtils
import com.ternparagliding.utils.io.FallbackWeatherAPI
import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.utils.io.LocationRequest
import com.ternparagliding.utils.io.WeatherAPI
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.utils.io.WindData
import com.ternparagliding.weather.ThermalQuality
import com.ternparagliding.weather.Verdict
import com.ternparagliding.weather.WeatherSourcePolicy
import com.ternparagliding.weather.assessFlyability
import com.ternparagliding.weather.assessOutlook
import com.ternparagliding.weather.assessQuality
import kotlinx.coroutines.runBlocking
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.osmdroid.util.GeoPoint

/**
 * Claim **K4 · Weather** — the promises about weather data and the safety
 * decisions it drives, by driving the real hazard / staleness / interpolation
 * logic and the weather cache (no screenshots, no emulator). See [docs/claims.md].
 *
 * What's testable today is the data + decision *foundation*. The synthesis on top
 * — a "flyable" go/no-go, thermal inference, the Skew-T diagram, the
 * country/fallback source policy — is not built yet and is logged as GAPs.
 */
class WeatherClaimsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var weatherCache: WeatherCache

    @Before
    fun setUp() {
        context = mockk<Context>()
        every { context.cacheDir } returns tempFolder.root
        weatherCache = WeatherCache(context)
        mockkObject(CountryUtils)
        every { CountryUtils.getCountryCodeFromGeoPoint(any(), any()) } returns "CH"
    }

    @After
    fun tearDown() {
        weatherCache.clearCache()
        unmockkObject(CountryUtils)
    }

    // ── builders ──────────────────────────────────────────────────────────
    private fun wx(
        cape: Double? = null,
        lightning: Double? = null,
        cloud: Double = 30.0,
        humidity: Double = 50.0,
        windSpeed: Double = 10.0,
        windDir: Double = 270.0,
        gust: Double = 15.0,
        windSpeed10m: Double? = null,
        precip: Double? = null,
        tsMs: Long = System.currentTimeMillis(),
    ) = WeatherData(
        wind = WindData(windSpeed, windDir, gust),
        temperature = 20.0, humidity = humidity, visibility = 10.0, // km
        pressure = 1013.0, cloudCover = cloud, timestamp = tsMs,
        cape = cape, lightningPotential = lightning,
        windSpeed10m = windSpeed10m, precipProbability = precip,
    )

    private fun period(weather: WeatherData, startMs: Long, text: String = "") =
        ForecastPeriod(startMs, startMs + 3_600_000, weather, text)

    private fun forecastOf(current: WeatherData) =
        WeatherForecast(current = current, daily = emptyList(), hourly = emptyList())

    /**
     * **CLAIM K4 · Correct (hazard logic).** The safety thresholds classify
     * correctly: CAPE > 500 → convective; lightning > 60% → thunderstorm (and
     * thunderstorm outranks convective); benign conditions raise NO false alarm.
     */
    @Test
    fun `correct - weather hazards classify against the safety thresholds`() {
        assertEquals(
            "CAPE > 500 must read as convective",
            HazardLevel.CONVECTIVE, classifyHazard(forecastOf(wx(cape = 600.0))),
        )
        assertEquals(
            "lightning > 60% must read as thunderstorm (outranks convective)",
            HazardLevel.THUNDERSTORM, classifyHazard(forecastOf(wx(cape = 600.0, lightning = 75.0))),
        )
        assertEquals(
            "cloud>85% & humidity>85% must read as convective",
            HazardLevel.CONVECTIVE, classifyHazard(forecastOf(wx(cape = 0.0, cloud = 90.0, humidity = 90.0))),
        )
        assertNull(
            "benign conditions must raise no false hazard",
            classifyHazard(forecastOf(wx(cape = 100.0, lightning = 10.0, cloud = 30.0, humidity = 40.0))),
        )
    }

    /**
     * **CLAIM K4 · Timely.** Weather is reported for the time the pilot will be
     * there: interpolated to the arrival time between two hourly periods — wind
     * direction wraps the short way (350°→10° through North), the rest linear.
     */
    @Test
    fun `timely - weather interpolates to the arrival time (circular wind, linear CAPE)`() {
        val t0 = 1_700_000_000_000L
        val start = period(wx(windDir = 350.0, windSpeed = 10.0, cape = 200.0), t0)
        val end = period(wx(windDir = 10.0, windSpeed = 20.0, cape = 600.0), t0 + 3_600_000)

        val mid = WeatherCache.interpolateWeather(start, end, t0 + 1_800_000) // halfway

        assertEquals("wind direction must interpolate through North, not backwards", 0.0, mid.wind.direction, 0.5)
        assertEquals("wind speed must interpolate linearly", 15.0, mid.wind.speed, 1e-6)
        assertEquals("CAPE must interpolate linearly", 400.0, mid.cape!!, 1e-6)
    }

    /**
     * **CLAIM K4 · Resilient (stale).** Weather older than the 4-hour window is
     * flagged stale, so the pilot is never silently shown old conditions; fresh
     * data is not flagged.
     */
    @Test
    fun `resilient - weather older than 4h is flagged stale, fresh is not`() {
        val now = System.currentTimeMillis()
        val stale = WeatherForecast(null, emptyList(), listOf(period(wx(), now - 5 * 3_600_000)))
        val fresh = WeatherForecast(null, emptyList(), listOf(period(wx(), now)))
        assertTrue("5h-old weather must be flagged stale", stale.isStale())
        assertFalse("current weather must not be flagged stale", fresh.isStale())
    }

    /**
     * **CLAIM K4 · Resilient (degraded source).** A degraded fallback gives
     * surface wind only — no CAPE, no lightning. The hazard logic must not crash
     * and must not *fabricate* a hazard from the missing upper-air data. (Exactly
     * what happens when we fall back from Open-Meteo to a surface-only source.)
     */
    @Test
    fun `resilient - a degraded source missing CAPE-lightning raises no false hazard`() {
        val degraded = forecastOf(wx(cape = null, lightning = null, cloud = 40.0, humidity = 50.0))
        assertNull("missing upper-air data must not fabricate a hazard", classifyHazard(degraded))
        assertFalse(degraded.hasConvectiveDanger())
        assertFalse(degraded.hasThunderstorm())
    }

    /**
     * **CLAIM K4 · Offline.** A forecast cached pre-flight is retrievable with no
     * network — the pilot keeps their weather in the air.
     */
    @Test
    fun `offline - a cached forecast is retrievable with no network`() {
        val loc = GeoPoint(47.0, 8.0)
        val fc = WeatherForecast(
            current = wx(windSpeed = 12.0),
            daily = emptyList(),
            hourly = listOf(period(wx(windSpeed = 12.0), System.currentTimeMillis())),
        )
        weatherCache.cacheWeather("route-1", loc, fc)

        assertTrue(
            "a cached forecast must be retrievable offline",
            weatherCache.queryNearbyWeather("route-1", loc, 10.0).isNotEmpty(),
        )
    }

    /**
     * **CLAIM K4 · Flyability.** A transparent, advisory read of the conditions —
     * GO / CAUTION / NO_GO, the verdict being the worst factor, with every reason
     * shown (never a black box). The universal pilot question, answered at a point.
     */
    @Test
    fun `flyability - conditions read GO, CAUTION or NO_GO with transparent reasons`() {
        // Calm + clear → GO, nothing to flag.
        val go = assessFlyability(wx(windSpeed = 8.0, gust = 12.0))
        assertEquals(Verdict.GO, go.verdict)
        assertTrue("a GO must have no flagged reasons", go.reasons.isEmpty())

        // Strong wind → not GO, and the wind reason is surfaced (transparency).
        val windy = assessFlyability(wx(windSpeed = 18.0, gust = 22.0))
        assertEquals(Verdict.CAUTION, windy.verdict)
        assertTrue("the wind reason must be shown", windy.reasons.any { it.factor == "wind" })

        // Thunderstorm → NO_GO, and it outranks everything.
        val storm = assessFlyability(wx(windSpeed = 8.0, lightning = 80.0))
        assertEquals(Verdict.NO_GO, storm.verdict)
        assertTrue("the storm reason must be shown", storm.reasons.any { it.factor == "thunderstorm" })

        // Too-strong wind → NO_GO.
        assertEquals(Verdict.NO_GO, assessFlyability(wx(windSpeed = 30.0)).verdict)
    }

    /**
     * **CLAIM K4 · Flyability (and soon).** Flyable now but a storm builds within
     * the window → "GO now, deteriorating to NO_GO at HH:MM" — the look-ahead a
     * pilot needs before committing to a flight.
     */
    @Test
    fun `flyability - flags when conditions deteriorate soon`() {
        val now = System.currentTimeMillis()
        val outlook = assessOutlook(
            WeatherForecast(
                current = wx(windSpeed = 8.0), // GO now
                daily = emptyList(),
                hourly = listOf(
                    period(wx(windSpeed = 8.0), now),
                    period(wx(windSpeed = 8.0), now + 3_600_000),
                    period(wx(windSpeed = 8.0, lightning = 80.0), now + 2 * 3_600_000), // storm in 2h
                ),
            ),
        )
        assertEquals("flyable now", Verdict.GO, outlook.now.verdict)
        assertEquals("must flag the coming storm", Verdict.NO_GO, outlook.deterioratesTo?.verdict)
        assertEquals("at the right time", now + 2 * 3_600_000, outlook.deterioratesAtMs)
    }

    /**
     * **CLAIM K4 · Flyability (visibility).** Low visibility is a safety factor —
     * you can't fly what you can't see. Fog → no-go; reduced → caution; clear → no flag.
     */
    @Test
    fun `flyability - low visibility is a safety factor`() {
        // visibility is in km in the model.
        val fog = assessFlyability(wx(windSpeed = 8.0).copy(visibility = 1.5))
        assertEquals(Verdict.NO_GO, fog.verdict)
        assertTrue("the visibility reason must be shown", fog.reasons.any { it.factor == "visibility" })

        assertEquals(Verdict.CAUTION, assessFlyability(wx(windSpeed = 8.0).copy(visibility = 4.0)).verdict)
        assertTrue("clear skies raise no visibility flag", assessFlyability(wx(windSpeed = 8.0)).reasons.none { it.factor == "visibility" })
    }

    /**
     * **CLAIM K4 · Flyability (wind detail).** With the 10 m wind now carried, a
     * clean gust *factor* (gust − 10 m wind) flags turbulence and the low-level
     * gradient (80 m − 10 m) flags shear — both real launch / top-land dangers.
     */
    @Test
    fun `flyability - gust factor and low-level gradient are flagged`() {
        val gusty = assessFlyability(wx(windSpeed = 12.0, gust = 26.0, windSpeed10m = 12.0))
        assertTrue("a big gust factor must not read GO", gusty.verdict != Verdict.GO)
        assertTrue("the gust-factor reason must be shown", gusty.reasons.any { it.factor == "gust factor" })

        val shear = assessFlyability(wx(windSpeed = 28.0, gust = 30.0, windSpeed10m = 8.0))
        assertTrue("the gradient reason must be shown", shear.reasons.any { it.factor == "gradient" })

        // Light, steady wind at both levels → clean GO, no false alarm.
        assertEquals(Verdict.GO, assessFlyability(wx(windSpeed = 9.0, gust = 12.0, windSpeed10m = 8.0)).verdict)
    }

    /**
     * **CLAIM K4 · Flyability (precip).** A high precipitation probability is a
     * no-go; a moderate chance is a caution; dry raises no flag.
     */
    @Test
    fun `flyability - precipitation probability gates flying`() {
        assertEquals(Verdict.NO_GO, assessFlyability(wx(windSpeed = 8.0, precip = 80.0)).verdict)
        assertEquals(Verdict.CAUTION, assessFlyability(wx(windSpeed = 8.0, precip = 50.0)).verdict)
        assertTrue("no precip flag when dry", assessFlyability(wx(windSpeed = 8.0, precip = 5.0)).reasons.none { it.factor == "precipitation" })
    }

    /**
     * **CLAIM K4 · Flyability (quality).** "Worth flying?" — thermal strength reads
     * from the lapse rate, an inversion caps the day, and overcast kills the lift.
     * The XC pilot's read, from the stability math (now testable, out of the UI).
     */
    @Test
    fun `quality - thermal strength reads from lapse rate, capped by inversion, killed by overcast`() {
        // Steep lapse (25 → 10 °C over ~1.5 km = ~10 °C/km), no inversion, clear → STRONG.
        val strong = assessQuality(wx(cloud = 20.0).copy(temperature = 25.0, temp850hPa = 10.0, temp925hPa = 18.0))
        assertEquals(ThermalQuality.STRONG, strong.thermal)

        // Shallow lapse (~2 °C/km) → WEAK.
        val weak = assessQuality(wx(cloud = 20.0).copy(temperature = 15.0, temp850hPa = 12.0, temp925hPa = 13.0))
        assertEquals(ThermalQuality.WEAK, weak.thermal)

        // Steep lapse but a warm layer aloft (850 > 925) → inversion caps it → WORKABLE.
        val capped = assessQuality(wx(cloud = 20.0).copy(temperature = 25.0, temp850hPa = 10.0, temp925hPa = 8.0))
        assertEquals(ThermalQuality.WORKABLE, capped.thermal)
        assertTrue("the inversion cap must be flagged", capped.cappedByInversion)

        // Overcast → no sun → no lift, whatever the lapse.
        assertEquals(ThermalQuality.NONE, assessQuality(wx(cloud = 95.0)).thermal)
    }

    /**
     * **CLAIM K4 · Source policy.** Specialize to the best free national model where
     * one exists (HRRR / AROME / ICON-D2…), with a tighter cache cadence for the
     * rapid-refresh models, and fall back to Open-Meteo's global `best_match`
     * everywhere else.
     */
    @Test
    fun `source policy - specializes per country with a global fallback`() {
        assertEquals("gfs_hrrr", WeatherSourcePolicy.modelFor("US"))
        assertEquals("meteofrance_arome_france_hd", WeatherSourcePolicy.modelFor("FR"))
        assertEquals("icon_d2", WeatherSourcePolicy.modelFor("DE"))
        assertEquals("best_match", WeatherSourcePolicy.modelFor("IN")) // unknown → global
        assertEquals("best_match", WeatherSourcePolicy.modelFor(null))
        assertTrue(
            "a rapid-refresh region must cache for less time than the global default",
            WeatherSourcePolicy.cacheTtlHoursFor("US") < WeatherSourcePolicy.cacheTtlHoursFor("IN"),
        )
    }

    /**
     * **CLAIM K4 · Source policy (fallback).** Open-Meteo aggregates the data but is
     * a single point of failure. When the primary is down, fall to an independent
     * secondary; if both fail, return null (the caller serves cache). Never throws.
     */
    @Test
    fun `source policy - falls back to the secondary when the primary is down, never crashes`() = runBlocking {
        val data = forecastOf(wx(windSpeed = 10.0))

        // Primary works → used; secondary untouched.
        val sec1 = FakeApi(forecastOf(wx(windSpeed = 99.0)))
        assertEquals(data, FallbackWeatherAPI(FakeApi(data), sec1).fetchForecast(46.0, 6.0))
        assertEquals("secondary must not be called when the primary works", 0, sec1.calls)

        // Primary returns nothing → secondary used.
        assertEquals(data, FallbackWeatherAPI(FakeApi(null), FakeApi(data)).fetchForecast(46.0, 6.0))

        // Primary throws → degrade to secondary, no crash.
        assertEquals(data, FallbackWeatherAPI(FakeApi(null, throws = true), FakeApi(data)).fetchForecast(46.0, 6.0))

        // Both fail → null (caller serves cache), still no crash.
        assertNull(FallbackWeatherAPI(FakeApi(null, throws = true), FakeApi(null)).fetchForecast(46.0, 6.0))
    }

    /** A controllable WeatherAPI stand-in for the fallback tests. */
    private class FakeApi(private val forecast: WeatherForecast?, private val throws: Boolean = false) : WeatherAPI {
        var calls = 0
        override suspend fun fetchForecast(lat: Double, lng: Double): WeatherForecast? {
            calls++
            if (throws) throw RuntimeException("provider down")
            return forecast
        }
        override suspend fun fetchBatchForecast(locations: List<LocationRequest>): Map<String, WeatherForecast?> = emptyMap()
        override suspend fun isAvailable(): Boolean = forecast != null
    }
}
