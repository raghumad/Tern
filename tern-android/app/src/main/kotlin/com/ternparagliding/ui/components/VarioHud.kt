package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.flight.FlightMetrics
import com.ternparagliding.redux.FlightDeckState
import com.ternparagliding.redux.PositionSource
import com.ternparagliding.redux.SettingsState
import com.ternparagliding.ui.theme.TernFontFamily
import com.ternparagliding.units.UnitPrefs
import com.ternparagliding.units.Units
import com.ternparagliding.weather.octantOf
import kotlin.math.roundToInt

private const val MS_TO_KNOTS = 1.943844
private val GRUPPO = TernFontFamily.gruppo

/** Append a value, then its unit as a small grey subscript (disambiguation, not primary info). */
private fun AnnotatedString.Builder.valUnit(value: String, unit: String, unitSizeSp: Int) {
    append(value)
    withStyle(
        SpanStyle(
            fontSize = unitSizeSp.sp,
            baselineShift = BaselineShift.Subscript,
            color = DeckColors.unitColor,
        ),
    ) { append(" $unit") }
}

/**
 * Glanceable flight-deck readout for the live vario (or bench replay), styled in Gruppo with the
 * deck colour code: climb (big, green-up/red-down) + thermal average, altitude + height-above-
 * takeoff, ground speed + glide ratio (only when gliding), the live circling wind, and a
 * source/battery line. Units render as muted subscripts. Translucent so the map reads through.
 */
@Composable
fun VarioHud(deck: FlightDeckState, settings: SettingsState, modifier: Modifier = Modifier) {
    val prefs = UnitPrefs(
        temperature = settings.temperatureUnit,
        speed = settings.speedUnit,
        distance = settings.distanceUnit,
        altitude = settings.altitudeUnit,
    )
    // Vario reads in the pilot's vertical unit: ft/min with feet, else m/s.
    val varioUnit = if (settings.altitudeUnit == "ft") "ft/min" else "m/s"

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DeckColors.panel(0.34f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // ── Climb (big) + thermal average ──
        val climb = deck.climbMs
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = buildAnnotatedString {
                    if (climb == null) append("—")
                    else valUnit(varioNum(climb, varioUnit), Units.varioSymbol(varioUnit), 16)
                },
                color = DeckColors.vario(climb),
                fontSize = 46.sp,
                fontFamily = GRUPPO,
            )
            deck.avgClimbMs?.let {
                Text(
                    text = "ø ${varioNum(it, varioUnit)}",
                    color = DeckColors.vario(it),
                    fontSize = 22.sp,
                    fontFamily = GRUPPO,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }

        // ── Altitude (MSL) + height-above-takeoff ──
        deck.altitudeM?.let { alt ->
            val height = deck.takeoffDatumM?.let { FlightMetrics.heightAboveTakeoff(alt, it) }
            val sym = Units.altitudeSymbol(prefs.altitude)
            Text(
                text = buildAnnotatedString {
                    valUnit("${Units.altitudeValue(alt, prefs.altitude).roundToInt()}", sym, 13)
                    if (height != null) {
                        append("   ▲ ")
                        valUnit("${Units.altitudeValue(height, prefs.altitude).roundToInt()}", sym, 13)
                    }
                },
                color = DeckColors.primary,
                fontSize = 22.sp,
                fontFamily = GRUPPO,
            )
        }

        // ── Ground speed + glide ratio (L/D only when gliding) ──
        deck.groundSpeedMs?.let { gs ->
            val ld = climb?.let { FlightMetrics.glideRatio(gs, it) }
            Text(
                text = buildAnnotatedString {
                    append("GS ")
                    valUnit(
                        "${Units.speedValue(gs * MS_TO_KNOTS, prefs.speed).roundToInt()}",
                        Units.speedSymbol(prefs.speed), 12,
                    )
                    if (ld != null) append("   ·   L/D %.1f".format(ld))
                },
                color = DeckColors.neutral,
                fontSize = 18.sp,
                fontFamily = GRUPPO,
            )
        }

        // ── Live circling wind ──
        if (deck.windFromDeg != null && deck.windSpeedMs != null) {
            val oct = octantOf(deck.windFromDeg!!)
            Text(
                text = buildAnnotatedString {
                    append("WIND ${deck.windFromDeg!!.toInt()}° $oct · ")
                    valUnit(
                        "${Units.speedValue(deck.windSpeedMs!! * MS_TO_KNOTS, prefs.speed).roundToInt()}",
                        Units.speedSymbol(prefs.speed), 11,
                    )
                },
                color = DeckColors.wind,
                fontSize = 16.sp,
                fontFamily = GRUPPO,
            )
        }

        // ── Source + battery ──
        val source = if (deck.positionSource == PositionSource.XC_TRACER) "◉ XC TRACER" else "◉ PHONE"
        val battery = deck.batteryPct?.let { "  🔋$it%" } ?: ""
        Text(
            "$source$battery",
            color = DeckColors.neutral,
            fontSize = 14.sp,
            fontFamily = GRUPPO,
        )
    }
}

/** Signed vario value without the unit suffix (whole ft/min, or one-decimal m/s). */
private fun varioNum(ms: Double, unit: String): String =
    if (unit == "ft/min") "%+d".format(Units.varioValue(ms, unit).roundToInt())
    else "%+.1f".format(ms)
