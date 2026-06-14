package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
private val DECK_SHADOW = Shadow(color = Color(0xDD000000), offset = Offset(1.5f, 1.5f), blurRadius = 5f)
private val SHADOW_STYLE = TextStyle(shadow = DECK_SHADOW)

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
 * Glanceable flight-deck readout, styled in Gruppo on the deck colour code. **Fixed width** so the
 * capsule doesn't breathe as the numbers change; each row is a label-left / value-right pair so the
 * figures stay aligned. Important figures (climb, altitude) are bold; units are muted subscripts;
 * every line carries a shadow for legibility over terrain. Translucent so the map reads through.
 */
@Composable
fun VarioHud(deck: FlightDeckState, settings: SettingsState, modifier: Modifier = Modifier) {
    val prefs = UnitPrefs(
        temperature = settings.temperatureUnit,
        speed = settings.speedUnit,
        distance = settings.distanceUnit,
        altitude = settings.altitudeUnit,
    )
    val varioUnit = if (settings.altitudeUnit == "ft") "ft/min" else "m/s"
    val climb = deck.climbMs

    Column(
        modifier = modifier
            .width(232.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DeckColors.panel(0.34f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ── Climb (big, bold) + thermal average ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(
                text = buildAnnotatedString {
                    if (climb == null) append("—")
                    else valUnit(varioNum(climb, varioUnit), Units.varioSymbol(varioUnit), 15)
                },
                color = DeckColors.vario(climb),
                fontSize = 40.sp,
                fontFamily = GRUPPO,
                fontWeight = FontWeight.Bold,
                style = SHADOW_STYLE,
            )
            deck.avgClimbMs?.let {
                Text(
                    text = "ø ${varioNum(it, varioUnit)}",
                    color = DeckColors.vario(it),
                    fontSize = 20.sp,
                    fontFamily = GRUPPO,
                    style = SHADOW_STYLE,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }

        // ── Altitude (bold) + height-above-takeoff ──
        deck.altitudeM?.let { alt ->
            val height = deck.takeoffDatumM?.let { FlightMetrics.heightAboveTakeoff(alt, it) }
            val sym = Units.altitudeSymbol(prefs.altitude)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(
                    text = buildAnnotatedString { valUnit("${Units.altitudeValue(alt, prefs.altitude).roundToInt()}", sym, 12) },
                    color = DeckColors.primary,
                    fontSize = 22.sp,
                    fontFamily = GRUPPO,
                    fontWeight = FontWeight.Bold,
                    style = SHADOW_STYLE,
                )
                if (height != null) {
                    Text(
                        text = buildAnnotatedString {
                            append("▲ ")
                            valUnit("${Units.altitudeValue(height, prefs.altitude).roundToInt()}", sym, 11)
                        },
                        color = DeckColors.neutral,
                        fontSize = 17.sp,
                        fontFamily = GRUPPO,
                        style = SHADOW_STYLE,
                    )
                }
            }
        }

        // ── Ground speed + glide ratio (L/D only when gliding) ──
        deck.groundSpeedMs?.let { gs ->
            val ld = climb?.let { FlightMetrics.glideRatio(gs, it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = buildAnnotatedString {
                        append("GS ")
                        valUnit("${Units.speedValue(gs * MS_TO_KNOTS, prefs.speed).roundToInt()}", Units.speedSymbol(prefs.speed), 11)
                    },
                    color = DeckColors.neutral, fontSize = 17.sp, fontFamily = GRUPPO, style = SHADOW_STYLE,
                )
                if (ld != null) {
                    Text("L/D %.1f".format(ld), color = DeckColors.neutral, fontSize = 17.sp, fontFamily = GRUPPO, style = SHADOW_STYLE)
                }
            }
        }

        // ── Live circling wind ──
        if (deck.windFromDeg != null && deck.windSpeedMs != null) {
            val oct = octantOf(deck.windFromDeg!!)
            Text(
                text = buildAnnotatedString {
                    append("WIND ${deck.windFromDeg!!.toInt()}° $oct · ")
                    valUnit("${Units.speedValue(deck.windSpeedMs!! * MS_TO_KNOTS, prefs.speed).roundToInt()}", Units.speedSymbol(prefs.speed), 10)
                },
                color = DeckColors.wind, fontSize = 15.sp, fontFamily = GRUPPO, style = SHADOW_STYLE,
            )
        }

        // ── Source + battery ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (deck.positionSource == PositionSource.XC_TRACER) "◉ XC TRACER" else "◉ PHONE",
                color = DeckColors.neutral, fontSize = 13.sp, fontFamily = GRUPPO, style = SHADOW_STYLE,
            )
            deck.batteryPct?.let {
                Text("🔋$it%", color = DeckColors.neutral, fontSize = 13.sp, fontFamily = GRUPPO, style = SHADOW_STYLE)
            }
        }
    }
}

/** Signed vario value without the unit suffix (whole ft/min, or one-decimal m/s). */
private fun varioNum(ms: Double, unit: String): String =
    if (unit == "ft/min") "%+d".format(Units.varioValue(ms, unit).roundToInt())
    else "%+.1f".format(ms)
