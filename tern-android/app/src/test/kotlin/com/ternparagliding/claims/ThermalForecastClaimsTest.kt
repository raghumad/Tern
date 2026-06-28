package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.utils.io.ProfileLevel
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.utils.io.WindData
import com.ternparagliding.weather.SiteContext
import com.ternparagliding.weather.ThermalQuality
import com.ternparagliding.weather.assessThermalDays
import com.ternparagliding.weather.convectiveVelocityScale
import org.junit.Test

/**
 * Claim **K4 · thermal outlook (w\*).** The XC pilot's timing read — *when today* lift works,
 * *how strong* (a numeric convective-velocity-scale climb rate), and *how high* it reaches.
 * These assert the pilot-visible outcome: a believable w* number, a working window bounded to
 * daylight, the peak hour, and honest degradation when the solar/boundary-layer inputs are
 * missing (qualitative strength survives; the number is withheld, not faked).
 */
class ThermalForecastClaimsTest {

    private val DAY = 20100L
    private fun hMs(h: Int) = DAY * 86_400_000L + h * 3_600_000L

    // A profile that yields a real parcel thermal top above a 300 m surface.
    private val prof = listOf(
        ProfileLevel(925, 750.0, 20.0, 5.0, 5.0, 270.0),
        ProfileLevel(850, 1500.0, 13.0, 5.0, 5.0, 270.0),
        ProfileLevel(700, 3000.0, 2.0, -5.0, 5.0, 270.0),
    )
    private val site = SiteContext(elevationM = 300.0)

    private fun hourWx(sw: Double, bl: Double, temp: Double = 25.0) = WeatherData(
        wind = WindData(5.0, 270.0, 8.0),
        temperature = temp, humidity = 50.0, visibility = 10.0,
        pressure = 1013.0, cloudCover = 20.0, timestamp = 0L,
        shortwaveRadiation = sw, boundaryLayerHeightM = bl, profile = prof,
    )

    /** A day: weak heating dawn/dusk, a strong convective midday (11–15 local). */
    private fun thermalDay(): WeatherForecast {
        val hours = (0..23).map { h ->
            val wx = when {
                h in 11..15 -> hourWx(sw = 700.0, bl = 2200.0)  // usable
                h in 6..20 -> hourWx(sw = 120.0, bl = 500.0)    // daylight but too weak
                else -> hourWx(sw = 0.0, bl = 100.0)            // night — no sun
            }
            ForecastPeriod(hMs(h), hMs(h) + 3_600_000L, wx, "h")
        }
        val daily = listOf(
            ForecastPeriod(hMs(0), hMs(0) + 86_400_000L, hourWx(0.0, 100.0), "d",
                sunriseMs = hMs(6), sunsetMs = hMs(20)),
        )
        return WeatherForecast(current = hours.first().weather, daily = daily, hourly = hours)
    }

    @Test
    fun `w-star is a believable climb rate and scales with sun and depth`() {
        // SW 600, zi 2000, 25C → ~2.1 m/s (Deardorff scale).
        val w = convectiveVelocityScale(600.0, 2000.0, 25.0)!!
        assertThat(w).isWithin(0.3).of(2.1)
        // Monotone: more sun and a deeper layer both strengthen w*.
        assertThat(convectiveVelocityScale(800.0, 2000.0, 25.0)!!).isGreaterThan(w)
        assertThat(convectiveVelocityScale(600.0, 3000.0, 25.0)!!).isGreaterThan(w)
    }

    @Test
    fun `no sun or no depth yields no number, not a fake zero`() {
        assertThat(convectiveVelocityScale(0.0, 2000.0, 25.0)).isNull()    // night
        assertThat(convectiveVelocityScale(null, 2000.0, 25.0)).isNull()   // source omits SW
        assertThat(convectiveVelocityScale(600.0, null, 25.0)).isNull()    // no boundary layer
    }

    @Test
    fun `the working window is the strong-heating block, bounded to daylight`() {
        val outlook = assessThermalDays(thermalDay(), site = site, maxDays = 1)[0]

        // Window is the 11:00–16:00 usable block (end = end of the 15:00 hour).
        assertThat(outlook.windowStartMs).isEqualTo(hMs(11))
        assertThat(outlook.windowEndMs).isEqualTo(hMs(16))
        // Night hours never appear; only daylight is scanned.
        assertThat(outlook.hours.none { it.startMs < hMs(6) || it.startMs > hMs(20) }).isTrue()
    }

    @Test
    fun `the peak reports a numeric climb, strength, and how high lift reaches`() {
        val outlook = assessThermalDays(thermalDay(), site = site, maxDays = 1)[0]
        val peak = outlook.peak!!

        assertThat(peak.wStarMs!!).isGreaterThan(1.5)        // a real midday climb
        assertThat(peak.startMs).isIn(hMs(11)..hMs(15))      // during the strong block
        assertThat(peak.strength.ordinal).isAtLeast(ThermalQuality.WORKABLE.ordinal)
        assertThat(peak.thermalTopMslM!!).isGreaterThan(site.elevationM!!) // tops out above launch
    }

    @Test
    fun `without solar input the strength survives but the number is withheld`() {
        // Strip the thermal-engine inputs (an older source / model that omits them).
        val hours = (6..20).map { h ->
            val wx = hourWx(700.0, 2200.0).copy(shortwaveRadiation = null, boundaryLayerHeightM = null)
            ForecastPeriod(hMs(h), hMs(h) + 3_600_000L, wx, "h")
        }
        val fc = WeatherForecast(
            current = hours.first().weather,
            daily = listOf(ForecastPeriod(hMs(0), hMs(0) + 86_400_000L, hours.first().weather, "d", hMs(6), hMs(20))),
            hourly = hours,
        )
        val outlook = assessThermalDays(fc, site = site, maxDays = 1)[0]

        assertThat(outlook.hours.all { it.wStarMs == null }).isTrue()        // no fabricated number
        // But the parcel-derived top is still computable from the profile.
        assertThat(outlook.peak?.thermalTopMslM).isNotNull()
    }
}
