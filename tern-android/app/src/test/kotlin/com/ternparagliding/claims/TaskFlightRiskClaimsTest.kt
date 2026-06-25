package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Waypoint
import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.utils.io.ProfileLevel
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.utils.io.WindData
import com.ternparagliding.weather.Verdict
import com.ternparagliding.weather.assessTaskFlightRisk
import org.junit.Test

/**
 * Claim **K2 · Flight risk (whole-task safety synthesis).** The old "storm risk"
 * alarm was convective-only and read "now", so a single ad-hoc point on a mildly
 * convective day flashed red on a 0 km task. Flight risk replaces it: the worst
 * *known* factor across every air-mass dimension + airspace, read at each ETA,
 * **advisory and transparent** — the pilot decides. These assert the pilot-visible
 * outcome: the verdict and the deciding factor, not an internal flag.
 */
class TaskFlightRiskClaimsTest {

    private fun wx(
        windSpeed: Double = 8.0,
        windDir: Double = 270.0,
        gust: Double = 10.0,
        cape: Double? = null,
        lightning: Double? = null,
    ) = WeatherData(
        wind = WindData(windSpeed, windDir, gust),
        temperature = 20.0, humidity = 50.0, visibility = 10.0,
        pressure = 1013.0, cloudCover = 30.0, timestamp = 0L,
        cape = cape, lightningPotential = lightning,
    )

    private fun forecast(w: WeatherData) =
        WeatherForecast(current = w, daily = emptyList(), hourly = emptyList())

    private fun wp(id: String, label: String) = Waypoint(id = id, lat = 40.0, lon = -105.0, label = label)

    /** The headline bug: a calm single point must NOT raise a false alarm. */
    @Test
    fun `a benign single point is GO, no false storm alarm`() {
        val w = wp("a", "WP1")
        val risk = assessTaskFlightRisk(listOf(w), mapOf("a" to forecast(wx())), emptyMap())

        assertThat(risk.verdict).isEqualTo(Verdict.GO)
        assertThat(risk.factors).isEmpty()
        assertThat(risk.anyData).isTrue()
    }

    /** Strong wind escalates the verdict and is named as the deciding factor. */
    @Test
    fun `strong wind drives the verdict to NO-GO and is the headline`() {
        val w = wp("a", "Windy WP")
        val risk = assessTaskFlightRisk(listOf(w), mapOf("a" to forecast(wx(windSpeed = 25.0))), emptyMap())

        assertThat(risk.verdict).isEqualTo(Verdict.NO_GO)
        assertThat(risk.headline?.label).isEqualTo("WIND")
        assertThat(risk.headline?.where).contains("Windy WP")
    }

    /** Convection is just one factor among the breadth — flagged, not the only thing we look at. */
    @Test
    fun `convective air is surfaced as a CONVECTION caution`() {
        val w = wp("a", "WP1")
        val risk = assessTaskFlightRisk(listOf(w), mapOf("a" to forecast(wx(cape = 700.0))), emptyMap())

        assertThat(risk.verdict).isEqualTo(Verdict.CAUTION)
        assertThat(risk.factors.map { it.label }).contains("CONVECTION")
    }

    /** Airspace folds into the single safety verdict (balanced: a CAUTION awareness cue), named. */
    @Test
    fun `airspace penetration folds into the verdict and is named`() {
        val w = wp("a", "WP1")
        val risk = assessTaskFlightRisk(
            listOf(w), mapOf("a" to forecast(wx())), emptyMap(),
            airspaceConflicts = listOf("Denver Class B"),
        )

        assertThat(risk.verdict).isEqualTo(Verdict.CAUTION)
        val airspace = risk.factors.first { it.label == "AIRSPACE" }
        assertThat(airspace.detail).contains("Denver Class B")
    }

    /** The verdict is the worst point on the task, and points to where the trouble is. */
    @Test
    fun `the worst waypoint on the task sets the verdict`() {
        val calm = wp("a", "Calm")
        val windy = wp("b", "Gnarly")
        val risk = assessTaskFlightRisk(
            listOf(calm, windy),
            mapOf("a" to forecast(wx()), "b" to forecast(wx(windSpeed = 25.0))),
            emptyMap(),
        )

        assertThat(risk.verdict).isEqualTo(Verdict.NO_GO)
        assertThat(risk.headline?.where).contains("Gnarly")
    }

    /** Missing forecast is reported transparently — never faked green or red. */
    @Test
    fun `a point with no forecast is reported as incomplete data, not a fake verdict`() {
        val w = wp("a", "WP1")
        val risk = assessTaskFlightRisk(listOf(w), emptyMap(), emptyMap())

        assertThat(risk.anyData).isFalse()
        assertThat(risk.dataComplete).isFalse()
        assertThat(risk.factors).isEmpty() // we don't invent a hazard we can't see
    }

    /** An ETA past sunset is a real safety cue: you'd be soaring/landing in the dark. */
    @Test
    fun `an ETA after sunset raises a DAYLIGHT caution`() {
        val dayStart = 1_700_000_000_000L
        val sunrise = dayStart + 6 * 3_600_000L
        val sunset = dayStart + 20 * 3_600_000L
        val eta = dayStart + 21 * 3_600_000L // an hour after sunset
        val fc = WeatherForecast(
            current = wx(),
            daily = listOf(ForecastPeriod(dayStart, dayStart + 24 * 3_600_000L, wx(), "", sunrise, sunset)),
            hourly = emptyList(),
        )
        // ETA and sun times are both true epoch now (offset 0) — they compare directly.
        val risk = assessTaskFlightRisk(
            listOf(wp("a", "Goal")), mapOf("a" to fc), mapOf("a" to eta),
        )

        assertThat(risk.verdict).isEqualTo(Verdict.CAUTION)
        assertThat(risk.factors.map { it.label }).contains("DAYLIGHT")
    }

    /** Terrain awareness: when the lowest cloudbase (MSL) sits below the task's highest
     *  terrain, that ground is in cloud — you can't top it in the clear. */
    @Test
    fun `cloudbase below the high terrain raises a TERRAIN caution`() {
        // Profile that yields a finite MSL cloudbase well under the 2000 m peak.
        val prof = listOf(
            ProfileLevel(925, 750.0, 20.0, 5.0, 5.0, 270.0),
            ProfileLevel(850, 1500.0, 13.0, 0.0, 5.0, 270.0),
            ProfileLevel(700, 3000.0, 2.0, -10.0, 5.0, 270.0),
        )
        val valleyWx = WeatherData(
            wind = WindData(8.0, 270.0, 10.0),
            temperature = 25.0, humidity = 50.0, visibility = 10.0,
            pressure = 1013.0, cloudCover = 30.0, timestamp = 0L, profile = prof,
        )
        val valley = Waypoint(id = "a", lat = 40.0, lon = -105.0, label = "Valley", alt = 300.0)
        val peak = Waypoint(id = "b", lat = 40.5, lon = -105.0, label = "Peak", alt = 2000.0)

        val risk = assessTaskFlightRisk(
            listOf(valley, peak),
            mapOf("a" to forecast(valleyWx), "b" to forecast(wx())),
            emptyMap(),
        )

        assertThat(risk.factors.map { it.label }).contains("TERRAIN")
    }
}
