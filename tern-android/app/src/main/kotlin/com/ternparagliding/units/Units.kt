package com.ternparagliding.units

import kotlin.math.roundToInt

/**
 * The pilot's four unit choices, as stored by the Settings pickers. Bundled so a
 * single value threads through the UI instead of four loose strings. Defaults are
 * the canonical units, so an un-set field formats unchanged.
 */
data class UnitPrefs(
    val temperature: String = "°C",
    val speed: String = "kn",
    val distance: String = "km",
    val altitude: String = "m",
)

/**
 * Pilot-preferred unit conversion + formatting. The app stores every weather value
 * in a single **canonical** unit (temperature °C, wind knots, distance km, altitude m
 * — see WeatherData); this is the one place that turns those into whatever the pilot
 * picked in Settings. Mirrors the iOS `MeasurementUnits` model (speed/magnitude/
 * xcDistance/temperature), keyed by the same symbol strings the Settings pickers store.
 *
 * Pure and total: an unknown/empty preference falls back to the canonical unit rather
 * than throwing — a missing setting must never break the weather screen.
 */
object Units {

    // ── Temperature — canonical Celsius ──────────────────────────────────────
    fun tempValue(celsius: Double, unit: String): Double = when (unit) {
        "°F" -> celsius * 9.0 / 5.0 + 32.0
        "K" -> celsius + 273.15
        else -> celsius // "°C"
    }

    fun tempSymbol(unit: String): String = when (unit) {
        "°F" -> "°F"
        "K" -> "K"
        else -> "°C"
    }

    /** e.g. 13 °C → "55°F". Degree symbols hug the number; Kelvin takes a space. */
    fun temp(celsius: Double, unit: String): String {
        val v = tempValue(celsius, unit).roundToInt()
        val sym = tempSymbol(unit)
        return if (sym == "K") "$v $sym" else "$v$sym"
    }

    // ── Speed — canonical knots ──────────────────────────────────────────────
    fun speedValue(knots: Double, unit: String): Double = when (unit) {
        "mph" -> knots * 1.150779
        "kph" -> knots * 1.852
        "m/s" -> knots * 0.514444
        else -> knots // "kn"
    }

    fun speedSymbol(unit: String): String = when (unit) {
        "mph" -> "mph"
        "kph" -> "km/h"
        "m/s" -> "m/s"
        else -> "kn"
    }

    fun speed(knots: Double, unit: String): String =
        "${speedValue(knots, unit).roundToInt()} ${speedSymbol(unit)}"

    // ── Horizontal distance / visibility — canonical km ──────────────────────
    fun distanceValue(km: Double, unit: String): Double = when (unit) {
        "mi" -> km * 0.621371
        "fur" -> km * 4.970970
        else -> km // "km"
    }

    fun distanceSymbol(unit: String): String = when (unit) {
        "mi" -> "mi"
        "fur" -> "fur"
        else -> "km"
    }

    fun distance(km: Double, unit: String): String =
        "${distanceValue(km, unit).roundToInt()} ${distanceSymbol(unit)}"

    // ── Altitude / cloudbase — canonical metres ──────────────────────────────
    fun altitudeValue(meters: Double, unit: String): Double = when (unit) {
        "ft" -> meters * 3.280840
        "in" -> meters * 39.37008
        else -> meters // "m"
    }

    fun altitudeSymbol(unit: String): String = when (unit) {
        "ft" -> "ft"
        "in" -> "in"
        else -> "m"
    }

    fun altitude(meters: Double, unit: String): String =
        "${altitudeValue(meters, unit).roundToInt()} ${altitudeSymbol(unit)}"

    // ── Vario / climb rate — canonical metres-per-second ──────────────────────
    fun varioValue(ms: Double, unit: String): Double = when (unit) {
        "ft/min" -> ms * 196.850394
        else -> ms // "m/s"
    }

    fun varioSymbol(unit: String): String = if (unit == "ft/min") "ft/min" else "m/s"

    /** Signed, e.g. +1.4 m/s or +276 ft/min. m/s keeps a decimal; ft/min is whole. */
    fun vario(ms: Double, unit: String): String = when (unit) {
        "ft/min" -> "%+d ft/min".format(varioValue(ms, unit).roundToInt())
        else -> "%+.1f m/s".format(ms)
    }
}
