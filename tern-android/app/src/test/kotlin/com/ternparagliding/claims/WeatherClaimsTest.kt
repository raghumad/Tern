package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.overlay.hazard.HazardLevel
import com.ternparagliding.overlay.hazard.classifyHazard
import com.ternparagliding.utils.cache.WeatherCache
import com.ternparagliding.utils.geo.CountryUtils
import com.ternparagliding.utils.geo.OfflineGeocoder
import com.ternparagliding.utils.io.FallbackWeatherAPI
import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.utils.io.LocationRequest
import com.ternparagliding.utils.io.WeatherAPI
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.utils.io.WindData
import com.ternparagliding.overlay.pgspot.siteContextOf
import com.ternparagliding.weather.Octant
import com.ternparagliding.weather.SiteContext
import com.ternparagliding.weather.Precip
import com.ternparagliding.weather.Sky
import com.ternparagliding.weather.ThermalQuality
import com.ternparagliding.weather.Verdict
import com.ternparagliding.weather.WeatherSourcePolicy
import com.ternparagliding.weather.assessFlyability
import com.ternparagliding.weather.assessSoarableDays
import com.ternparagliding.weather.octantOf
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
import org.junit.Assert.assertNotNull
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
 * Covers the data + decision foundation, the Flyability synthesis, and the live
 * source policy (per-country model into the URL + MET Norway fallback). What's
 * still GAP and logged in docs/claims.md: the quantitative thermal outlook and the
 * Skew-T stability diagram.
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
        windDir10m: Double? = null,
        precip: Double? = null,
        tsMs: Long = System.currentTimeMillis(),
    ) = WeatherData(
        wind = WindData(windSpeed, windDir, gust),
        temperature = 20.0, humidity = humidity, visibility = 10.0, // km
        pressure = 1013.0, cloudCover = cloud, timestamp = tsMs,
        cape = cape, lightningPotential = lightning,
        windSpeed10m = windSpeed10m, windDirection10m = windDir10m, precipProbability = precip,
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

    /**
     * **CLAIM K4 · Source wiring (model into the live URL).** The policy isn't just
     * a lookup table — the live Open-Meteo fetch URL must actually carry the
     * country's specialized model. Resolve the country offline, and the built URL
     * specializes (`&models=gfs_hrrr` over the US) or omits `&models=` to let
     * Open-Meteo auto-pick (`best_match`) for unknown/global locations.
     */
    @Test
    fun `source wiring - the live fetch URL carries the per-country model`() {
        mockkObject(OfflineGeocoder)
        try {
            val api = com.ternparagliding.utils.io.OpenMeteoWeatherAPI()

            // Over the US → HRRR is threaded into the live URL.
            every { OfflineGeocoder.getCountryCode(any()) } returns "US"
            val usUrl = api.buildUrl(40.0, -105.0)
            assertTrue("US fetch must specialize to HRRR", usUrl.contains("&models=gfs_hrrr"))

            // France → AROME.
            every { OfflineGeocoder.getCountryCode(any()) } returns "FR"
            assertTrue(api.buildUrl(45.9, 6.1).contains("&models=meteofrance_arome_france_hd"))

            // Unknown country → no &models= (degrade to Open-Meteo's best_match).
            every { OfflineGeocoder.getCountryCode(any()) } returns "IN"
            assertFalse("global default must omit &models=", api.buildUrl(32.2, 76.7).contains("&models="))

            // Over water / unresolved → also best_match, and never throws.
            every { OfflineGeocoder.getCountryCode(any()) } returns null
            assertFalse(api.buildUrl(0.0, -150.0).contains("&models="))
        } finally {
            unmockkObject(OfflineGeocoder)
        }
    }

    /**
     * **CLAIM K4 · Source wiring (MET Norway secondary).** The independent fallback
     * provider parses into a real forecast: surface fields correct and unit-converted
     * (wind m/s → knots), precipitation from the forward block — while the fields it
     * can't supply (CAPE, lightning, pressure-level temps) come back null so the
     * convective/stability reads gracefully degrade rather than false-alarm.
     */
    @Test
    fun `source wiring - MET Norway parses into a degraded surface forecast`() {
        // wind 5 m/s @ 230°, gust 8 m/s, 18.5°C, 40% cloud, 1013 hPa, 20% precip next hour.
        val json = """
            {"properties":{"timeseries":[
              {"time":"2026-06-12T13:00:00Z","data":{
                "instant":{"details":{
                  "air_temperature":18.5,"wind_speed":5.0,"wind_speed_of_gust":8.0,
                  "wind_from_direction":230.0,"relative_humidity":55.0,
                  "cloud_area_fraction":40.0,"air_pressure_at_sea_level":1013.0}},
                "next_1_hours":{"details":{"probability_of_precipitation":20.0,"precipitation_amount":0.2}}}},
              {"time":"2026-06-12T14:00:00Z","data":{
                "instant":{"details":{
                  "air_temperature":19.0,"wind_speed":6.0,"wind_from_direction":240.0,
                  "relative_humidity":50.0,"cloud_area_fraction":35.0,"air_pressure_at_sea_level":1012.0}}}}
            ]}}
        """.trimIndent()

        val forecast = com.ternparagliding.utils.io.parseMetNorwayCompact(json)
        assertNotNull("MET Norway must parse into a forecast", forecast)
        val now = forecast!!.current!!

        // Surface fields correct + unit-converted (m/s → knots).
        assertEquals(18.5, now.temperature, 0.01)
        assertEquals(40.0, now.cloudCover, 0.01)
        assertEquals(230.0, now.wind.direction, 0.01)
        assertEquals(5.0 * WeatherAPI.MS_TO_KNOTS, now.wind.speed, 0.01)
        assertEquals(8.0 * WeatherAPI.MS_TO_KNOTS, now.wind.gust, 0.01)
        assertEquals(5.0 * WeatherAPI.MS_TO_KNOTS, now.windSpeed10m!!, 0.01)
        assertEquals(20.0, now.precipProbability!!, 0.01)

        // Surface-only: the fields MET can't supply degrade to null (no false convective alarm).
        assertNull(now.cape)
        assertNull(now.lightningPotential)
        assertNull(now.temp850hPa)
        assertNull("degraded surface-only source must raise no false hazard", classifyHazard(forecast)) // no CAPE/lightning → null

        // The full hourly series is carried (not just current).
        assertEquals(2, forecast.hourly.size)
    }

    /**
     * **CLAIM K4×K3 · Site-aware Flyability (wind vs the hill).** A launch only works
     * in certain wind directions (PGE orientation). With meaningful wind, a cross/behind
     * wind is a no-go *for this launch* even when the air mass is otherwise GO — the
     * unknown a weekend pilot can't compute. Light wind makes direction moot.
     */
    @Test
    fun `site - wind on the hill vs cross-behind gates this launch`() {
        // A west-facing launch: W ideal, SW/NW workable, everything else a no.
        val westFacing = SiteContext(
            elevationM = 1500.0,
            orientations = mapOf(Octant.W to 2, Octant.SW to 1, Octant.NW to 1),
        )
        val benign = wx(windSpeed = 12.0, gust = 14.0, cape = 50.0) // air-mass GO

        // Wind from the W (270°) → straight on the hill, no orientation downgrade.
        assertEquals(Verdict.GO,
            assessFlyability(benign.copy(wind = WindData(12.0, 270.0, 14.0)), site = westFacing).verdict)

        // Wind from the N (0°) → cross/behind. The same air with no site reads GO,
        // but THIS launch faces away → NO_GO. ("the air is flyable, but not here today")
        val crossWind = benign.copy(wind = WindData(12.0, 0.0, 14.0))
        assertEquals("air alone is flyable", Verdict.GO, assessFlyability(crossWind).verdict)
        assertEquals("but this launch faces away from a N wind", Verdict.NO_GO,
            assessFlyability(crossWind, site = westFacing).verdict)

        // Wind from the SW (225°, workable = 1) → CAUTION.
        assertEquals(Verdict.CAUTION,
            assessFlyability(benign.copy(wind = WindData(12.0, 225.0, 14.0)), site = westFacing).verdict)

        // Only true dead-calm (≤ ~2 kt forward launch) ignores direction.
        assertEquals(Verdict.GO,
            assessFlyability(benign.copy(wind = WindData(2.0, 0.0, 3.0)), site = westFacing).verdict)
    }

    /**
     * **CLAIM K4×K3 · Regression (the Boulder 5 kt SW bug).** A *light* wind from a
     * wrong direction is still dangerous at a ridge/soaring launch (lee/rotor, can't
     * soar) — it must NOT read flyable. Boulder faces NE/E/SE; a 5 kt SW (235°) wind
     * is a no-go. (Previously slipped under a too-high 6 kt "direction doesn't matter"
     * threshold and read GO.)
     */
    @Test
    fun `site - a light wind from the wrong direction is still a no-go (Boulder 5kt SW)`() {
        val boulder = SiteContext(
            elevationM = 1905.0,
            orientations = mapOf(
                Octant.N to 0, Octant.NE to 2, Octant.E to 2, Octant.SE to 2,
                Octant.S to 1, Octant.SW to 0, Octant.W to 0, Octant.NW to 0,
            ),
        )
        // 5 kt from 235° (SW, score 0) — the exact reported case.
        val fly = assessFlyability(wx(windSpeed = 5.0, windDir = 235.0, gust = 6.0), site = boulder)
        assertEquals("5 kt SW at an E-facing launch must be NO-GO", Verdict.NO_GO, fly.verdict)
        assertTrue("must name the wind-direction reason",
            fly.reasons.any { it.factor == "wind direction" })
    }

    /**
     * **CLAIM K4×K3 · Site-aware Flyability (cloudbase vs launch).** A base sitting at
     * launch height means launching into cloud — a no-go. The quality read expresses
     * cloudbase relative to the launch (height above launch + MSL) when elevation is known.
     */
    @Test
    fun `site - cloudbase at launch height is in-cloud, quality reads relative to launch`() {
        val site = SiteContext(elevationM = 1500.0, orientations = mapOf(Octant.W to 2))
        // Very humid → LCL only ~50 m above launch → likely in cloud.
        val humid = wx(windSpeed = 8.0, gust = 9.0, windDir = 270.0, humidity = 98.0, cape = 0.0)

        val fly = assessFlyability(humid, site = site)
        assertEquals(Verdict.NO_GO, fly.verdict)
        assertTrue("must name the cloudbase reason", fly.reasons.any { it.factor == "cloudbase" })

        // Same humid air with no site context: cloudbase isn't a safety gate → GO.
        assertEquals(Verdict.GO, assessFlyability(humid).verdict)

        // Quality expresses cloudbase relative to launch + MSL.
        val q = assessQuality(humid, site = site)
        assertTrue("quality note expresses cloudbase above launch in MSL",
            q.notes.any { it.contains("MSL") && it.contains("launch") })
    }

    /**
     * **CLAIM K4×K3 · The orientation check follows the SURFACE wind.** Friction can
     * veer the 10 m (what hits the launch) and 80 m (aloft) winds 20°+ apart — enough
     * to flip the octant. The launch decision must use the wind the pilot actually
     * feels, not the aloft wind (which is for the gradient).
     */
    @Test
    fun `site - orientation uses the surface wind, not the 80m aloft wind`() {
        val westFacing = SiteContext(orientations = mapOf(Octant.W to 2, Octant.SW to 1))
        // Aloft (80 m) from the W (270°, ideal) but surface (10 m) from the S (180°, a no).
        // Decision must follow the surface → NO_GO (if it wrongly used aloft it'd read GO).
        val w = wx(
            windSpeed = 12.0, windDir = 270.0,      // 80 m aloft → W, ideal
            windSpeed10m = 10.0, windDir10m = 180.0, // 10 m surface → S, score 0
            cape = 50.0, gust = 14.0,
        )
        assertEquals(Verdict.NO_GO, assessFlyability(w, site = westFacing).verdict)
    }

    /** **CLAIM K4×K3 · Site context.** The wind-from octant maps correctly (and wraps). */
    @Test
    fun `site - wind-from direction maps to the right octant`() {
        assertEquals(Octant.N, octantOf(0.0))
        assertEquals(Octant.N, octantOf(359.0))
        assertEquals(Octant.E, octantOf(90.0))
        assertEquals(Octant.SW, octantOf(235.0))
        assertEquals(Octant.W, octantOf(270.0))
        assertEquals(Octant.N, octantOf(720.0)) // wraps past 360
    }

    /**
     * **CLAIM K3×K4 · Site context parsing.** PGE's launch geometry (takeoff_altitude +
     * the eight orientation octants) reads into a SiteContext — nested or flat, tolerating
     * numeric strings, degrading cleanly on missing fields.
     */
    @Test
    fun `site - PGE properties parse into a SiteContext`() {
        val pge = mapOf<String, Any>(
            "properties" to mapOf(
                "name" to "Gringlay",
                "takeoff_altitude" to 444,
                "W" to 2, "SW" to 1, "NW" to "1", "N" to 0, // NW as a numeric string
            )
        )
        val ctx = siteContextOf(pge)
        assertEquals(444.0, ctx.elevationM!!, 0.01)
        assertEquals(2, ctx.orientations[Octant.W])
        assertEquals(1, ctx.orientations[Octant.SW])
        assertEquals(1, ctx.orientations[Octant.NW]) // numeric string tolerated
        assertEquals(0, ctx.orientations[Octant.N])
        assertTrue(ctx.hasOrientation)

        // Missing altitude / orientation degrade cleanly (no crash, no fake data).
        val empty = siteContextOf(mapOf("properties" to mapOf("name" to "x")))
        assertNull(empty.elevationM)
        assertFalse(empty.hasOrientation)
    }

    // ── soarable-window scan helpers ────────────────────────────────────────
    private val DAY = 20000L // arbitrary day index; hour h → local hour h
    private fun hMs(h: Int) = DAY * 86_400_000L + h * 3_600_000L
    private fun dayForecast(
        hourly: List<ForecastPeriod>, sunriseH: Int = 6, sunsetH: Int = 20, withSun: Boolean = true,
    ): WeatherForecast {
        val daily = if (withSun) listOf(
            ForecastPeriod(DAY * 86_400_000L, DAY * 86_400_000L + 86_400_000L, wx(), "d",
                sunriseMs = hMs(sunriseH), sunsetMs = hMs(sunsetH)),
        ) else emptyList()
        return WeatherForecast(current = hourly.firstOrNull()?.weather, daily = daily, hourly = hourly)
    }

    /** West-facing launch used across the soarable tests (W ideal, SW workable). */
    private val westFacing = SiteContext(orientations = mapOf(Octant.W to 2, Octant.SW to 1))
    private fun goHour(h: Int) = period(wx(windSpeed = 12.0, windDir = 270.0, windSpeed10m = 10.0, windDir10m = 270.0, gust = 14.0, cape = 50.0), hMs(h))
    private fun crossHour(h: Int) = period(wx(windSpeed = 12.0, windDir = 0.0, windSpeed10m = 10.0, windDir10m = 0.0, gust = 14.0, cape = 50.0), hMs(h))
    private fun calmHour(h: Int) = period(wx(windSpeed = 2.0, windDir = 270.0, windSpeed10m = 2.0, windDir10m = 270.0), hMs(h))

    /**
     * **CLAIM K4 · Soarable window.** The scan finds *when today* the site is flyable —
     * a contiguous GO run, reported as the best window. (The "when to show up" read.)
     */
    @Test
    fun `soarable - finds the contiguous flyable window in the day`() {
        // GO 10:00–12:00; cross-wind (NO_GO) the rest of daylight; calm at night.
        val hours = (0..23).map { h ->
            when {
                h in 10..12 -> goHour(h)
                h in 6..20 -> crossHour(h)
                else -> calmHour(h)
            }
        }
        val days = assessSoarableDays(dayForecast(hours), site = westFacing, maxDays = 1)
        assertEquals(1, days.size)
        val best = days[0].best!!
        assertEquals("soarable window must be the GO run", Verdict.GO, best.verdict)
        assertEquals("window starts at 10:00", hMs(10), best.startMs)
        assertEquals("window ends at 13:00 (end of the 12:00 hour)", hMs(13), best.endMs)
    }

    /**
     * **CLAIM K4 · Soarable is daylight-bound.** Even if the air is flyable at 03:00,
     * you can't soar in the dark — windows are clipped to sunrise→sunset.
     */
    @Test
    fun `soarable - windows are bounded to daylight`() {
        val hours = (0..23).map { goHour(it) } // flyable around the clock
        val day = assessSoarableDays(dayForecast(hours, sunriseH = 6, sunsetH = 20), site = westFacing, maxDays = 1)[0]
        val best = day.best!!
        assertEquals("starts at sunrise, not midnight", hMs(6), best.startMs)
        assertEquals("ends at sunset hour, not 24:00", hMs(21), best.endMs) // 20:00 hour ends at 21:00
        assertTrue("no window may include night hours", day.windows.all { it.startMs >= hMs(6) })
    }

    /**
     * **CLAIM K4 · Marginal fallback.** A day with no GO hour but some CAUTION still
     * reports a window — labeled marginal, not hidden. (Spedmo's "Marginal window".)
     */
    @Test
    fun `soarable - a day with only marginal hours reports a marginal window`() {
        // 18 kt on-direction → CAUTION (strong but flyable), all daylight.
        val hours = (0..23).map { h ->
            if (h in 6..20) period(wx(windSpeed = 18.0, windDir = 270.0, windSpeed10m = 18.0, windDir10m = 270.0, gust = 15.0, cape = 50.0), hMs(h))
            else calmHour(h)
        }
        val best = assessSoarableDays(dayForecast(hours), site = westFacing, maxDays = 1)[0].best!!
        assertEquals(Verdict.CAUTION, best.verdict)
    }

    /**
     * **CLAIM K4 · Daily digest.** The plain summary a pilot scans: prevailing wind +
     * on/off direction, gust, temp range, sky, precip — all from the day's daylight hours.
     */
    @Test
    fun `soarable - digest summarizes the day's prevailing conditions`() {
        val hours = (0..23).map { h ->
            if (h in 6..20) period(
                wx(windSpeed = 12.0, windDir = 270.0, windSpeed10m = 10.0, windDir10m = 270.0,
                    gust = 18.0, cloud = 10.0, cape = 50.0),
                hMs(h),
            ) else calmHour(h)
        }
        val digest = assessSoarableDays(dayForecast(hours), site = westFacing, maxDays = 1)[0].digest!!
        assertEquals("prevailing wind is from the W", Octant.W, digest.dominantOctant)
        assertEquals("W is on the hill for a west-facing site", true, digest.onDirection)
        assertEquals(18.0, digest.maxGustKt, 0.01)
        assertEquals(Sky.CLEAR, digest.sky)       // 10% cloud
        assertEquals(Precip.DRY, digest.precip)   // no precip probability
    }

    /**
     * **CLAIM K4 · Soarable degrades without sun times.** A surface-only source (no
     * daily sun times) still produces a read, bounded by a daytime band — never an
     * all-night "soarable", never a crash.
     */
    @Test
    fun `soarable - degrades to a daytime band when sun times are missing`() {
        val hours = (0..23).map { goHour(it) }
        val day = assessSoarableDays(dayForecast(hours, withSun = false), site = westFacing, maxDays = 1)[0]
        assertNull("no sun times available", day.sunriseMs)
        val best = day.best!!
        assertTrue("window must not start before the fallback daytime band", best.startMs >= hMs(5))
        assertTrue("window must not run past the fallback daytime band", best.endMs <= hMs(21))
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
