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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.flight.FlightMetrics
import com.ternparagliding.redux.FlightDeckState
import com.ternparagliding.redux.PositionSource
import com.ternparagliding.redux.SettingsState
import com.ternparagliding.units.UnitPrefs
import com.ternparagliding.units.Units
import com.ternparagliding.weather.octantOf
import kotlin.math.roundToInt

private const val MS_TO_KNOTS = 1.943844
private val GRUPPO = DeckColors.font
private val DECK_SHADOW = Shadow(color = Color(0xFF000000), offset = Offset(1.5f, 1.5f), blurRadius = 6f)
private val SHADOW_STYLE = TextStyle(shadow = DECK_SHADOW)
// Deck capsule-text rule (see DeckColors): bright value, slightly-quieter-but-bright labels/units.
private val LABEL_COLOR = DeckColors.label
private val VALUE_COLOR = DeckColors.value

/**
 * Glanceable flight-deck readout — the **secondary** panel. Instantaneous climb and altitude are
 * deliberately **not** repeated here: the tape already shows both prominently, so this capsule
 * carries only what the tape doesn't put a number on — thermal average, height gained, ground
 * speed, glide ratio, wind, and the source/battery line.
 *
 * Styled in Gruppo on the deck colour code. **Fixed width** so it doesn't breathe, and every row is
 * a label-left / value-right pair where the *unit is pinned to the right edge* — so as a value grows
 * from one digit to three the number expands leftward into the gap and the unit never jumps.
 * Translucent, shadowed for legibility over terrain.
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
    val speedSym = Units.speedSymbol(prefs.speed)
    val altSym = Units.altitudeSymbol(prefs.altitude)

    Column(
        modifier = modifier
            .width(196.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DeckColors.panel(0.62f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Thermal average for the current climb — the tape shows this only as an amber tick.
        deck.avgClimbMs?.let {
            MetricRow("AVG", varioNum(it, varioUnit), Units.varioSymbol(varioUnit), DeckColors.vario(it), bold = true)
        }

        // Height gained above takeoff — the tape marks the launch datum but doesn't number it.
        deck.altitudeM?.let { alt ->
            deck.takeoffDatumM?.let { datum ->
                val gain = FlightMetrics.heightAboveTakeoff(alt, datum)
                MetricRow("GAIN", "${Units.altitudeValue(gain, prefs.altitude).roundToInt()}", altSym, VALUE_COLOR)
            }
        }

        // Ground speed.
        deck.groundSpeedMs?.let { gs ->
            MetricRow("GS", "${Units.speedValue(gs * MS_TO_KNOTS, prefs.speed).roundToInt()}", speedSym, VALUE_COLOR)
            // Contextual cell — the one read that changes with phase, via the claim-tested
            // FlightMetrics.hudContext selector: L/D while gliding, a climb glyph while
            // climbing (the gain number lives in the GAIN row above), and the gap to cloudbase
            // when near it. The row never jumps. (Cloudbase is dormant until weather cloudbase
            // is threaded into the deck — TODO; the gliding/climbing cases are live today.)
            when (val ctx = FlightMetrics.hudContext(
                climbMs = deck.climbMs,
                groundSpeedMs = gs,
                altitudeMslM = deck.altitudeM,
                takeoffDatumM = deck.takeoffDatumM,
                cloudBaseMslM = null,
            )) {
                is FlightMetrics.HudContext.GlideRatio -> MetricRow("L/D", "%.1f".format(ctx.ld), null, VALUE_COLOR)
                is FlightMetrics.HudContext.HeightGain -> MetricRow("L/D", "▲ climb", null, DeckColors.lift)
                is FlightMetrics.HudContext.CloudbaseGap ->
                    MetricRow("BASE", "${Units.altitudeValue(ctx.gapM, prefs.altitude).roundToInt()}", altSym, DeckColors.wind)
                FlightMetrics.HudContext.None -> MetricRow("L/D", "—", null, VALUE_COLOR)
            }
        }

        // Live circling wind — direction in the label, speed pinned right.
        if (deck.windFromDeg != null && deck.windSpeedMs != null) {
            val oct = octantOf(deck.windFromDeg!!)
            MetricRow(
                "WIND ${deck.windFromDeg!!.toInt()}°$oct",
                "${Units.speedValue(deck.windSpeedMs!! * MS_TO_KNOTS, prefs.speed).roundToInt()}",
                speedSym, DeckColors.wind, valueSizeSp = 16,
            )
        }

        // Source + battery.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (deck.positionSource == PositionSource.XC_TRACER) "◉ XC TRACER" else "◉ PHONE",
                color = LABEL_COLOR, fontSize = 13.sp, fontFamily = GRUPPO, style = SHADOW_STYLE,
            )
            deck.batteryPct?.let {
                Text("🔋$it%", color = LABEL_COLOR, fontSize = 13.sp, fontFamily = GRUPPO, style = SHADOW_STYLE)
            }
        }
    }
}

/**
 * One reading: muted label on the left, value on the right with its unit as a pinned grey
 * subscript. Because the value is the row's right-hand element, its right edge (the unit) stays
 * fixed while the number grows leftward — so the unit never shifts as the magnitude changes.
 */
@Composable
private fun MetricRow(
    label: String,
    value: String,
    unit: String?,
    color: Color,
    valueSizeSp: Int = 18,
    bold: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, color = LABEL_COLOR, fontSize = 13.sp, fontFamily = GRUPPO, style = SHADOW_STYLE)
        // Number + unit aligned by baseline: since the units have no descenders, baseline alignment
        // puts the smaller unit's bottom on the number's bottom — a clean bottom-aligned suffix.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = color,
                fontSize = valueSizeSp.sp,
                fontFamily = GRUPPO,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                style = SHADOW_STYLE,
                modifier = Modifier.alignByBaseline(),
            )
            if (unit != null) {
                Text(
                    " $unit",
                    color = DeckColors.unitColor,
                    fontSize = (valueSizeSp * 0.62f).sp,
                    fontFamily = GRUPPO,
                    style = SHADOW_STYLE,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

/** Signed vario value without the unit suffix (whole ft/min, or one-decimal m/s). */
private fun varioNum(ms: Double, unit: String): String =
    if (unit == "ft/min") "%+d".format(Units.varioValue(ms, unit).roundToInt())
    else "%+.1f".format(ms)
