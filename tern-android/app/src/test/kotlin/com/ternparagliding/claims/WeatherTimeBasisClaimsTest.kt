package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.utils.io.OpenMeteoWeatherAPI
import com.ternparagliding.utils.io.siteTimeZone
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * Claim **K4 · forecast time basis is true epoch.** The old convention parsed Open-Meteo's
 * `timezone=auto` wall-clock strings *as UTC* and carried that fiction everywhere — so a
 * true-epoch ETA (TrajectoryAnalyzer) sampled the wrong forecast hour, off by the site's UTC
 * offset (the bug that printed "sunrise 23:29" and mis-read arrival weather). The root fix:
 * every forecast timestamp is a genuine instant, and the site's offset is carried separately
 * for display only. These assert the instant is correct AND that it still *reads back* as the
 * site's wall-clock time when formatted with [siteTimeZone].
 */
class WeatherTimeBasisClaimsTest {

    // Open-Meteo at a UTC+2 site: hourly/daily are local wall-clock strings + utc_offset_seconds.
    private val json = """
        {
          "utc_offset_seconds": 7200,
          "hourly": {
            "time": ["2026-06-21T13:00","2026-06-21T14:00"],
            "temperature_2m": [20.0,21.0],
            "relative_humidity_2m": [50,50],
            "wind_speed_10m": [5,5],
            "wind_direction_10m": [270,270],
            "wind_gusts_10m": [8,8],
            "pressure_msl": [1013,1013],
            "cloud_cover": [30,30]
          },
          "daily": {
            "time": ["2026-06-21"],
            "temperature_2m_max": [25],
            "temperature_2m_min": [12],
            "wind_speed_10m_max": [10],
            "wind_direction_10m_dominant": [270],
            "sunrise": ["2026-06-21T06:00"],
            "sunset": ["2026-06-21T21:00"]
          }
        }
    """.trimIndent()

    /** True-epoch instant of a wall-clock time at a given UTC offset (seconds). */
    private fun instant(local: String, offsetSec: Int): Long =
        LocalDateTime.parse(local).toInstant(ZoneOffset.ofTotalSeconds(offsetSec)).toEpochMilli()

    @Test
    fun `hourly timestamps are the true instant, not wall-clock-as-UTC`() {
        val fc = OpenMeteoWeatherAPI().parseForecastForTesting(json, 46.0, 6.0)!!

        assertThat(fc.utcOffsetSeconds).isEqualTo(7200)
        // 13:00 at UTC+2 is 11:00Z — the genuine instant, three-thousand-six-hundred-times-two
        // ms earlier than the old as-UTC reading.
        assertThat(fc.hourly[0].startTime).isEqualTo(instant("2026-06-21T13:00", 7200))
        assertThat(fc.hourly[1].startTime).isEqualTo(instant("2026-06-21T14:00", 7200))
        // Endpoints stay one hour apart.
        assertThat(fc.hourly[0].endTime - fc.hourly[0].startTime).isEqualTo(3_600_000L)
    }

    @Test
    fun `sun times are true epoch too, so daylight compares directly to ETAs`() {
        val fc = OpenMeteoWeatherAPI().parseForecastForTesting(json, 46.0, 6.0)!!

        assertThat(fc.daily[0].sunriseMs).isEqualTo(instant("2026-06-21T06:00", 7200))
        assertThat(fc.daily[0].sunsetMs).isEqualTo(instant("2026-06-21T21:00", 7200))
    }

    @Test
    fun `formatting a true-epoch timestamp in the site zone reads back the local wall clock`() {
        val fc = OpenMeteoWeatherAPI().parseForecastForTesting(json, 46.0, 6.0)!!
        val hm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = siteTimeZone(fc.utcOffsetSeconds) }

        // The instant is 11:00Z, but at the launch (UTC+2) it is 13:00 — what the pilot must see.
        assertThat(hm.format(Date(fc.hourly[0].startTime))).isEqualTo("13:00")
        assertThat(hm.format(Date(fc.daily[0].sunsetMs!!))).isEqualTo("21:00")
    }
}
