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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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

private const val MS_TO_KNOTS = 1.943844
private val HUD_BG = Color(0xFF0F1117).copy(alpha = 0.62f)
private val MUTED = Color(0xFF9CA3AF)

/**
 * A glanceable flight-deck readout for the live external vario (or bench replay): climb (the
 * number that matters most, big + color-coded) with the thermal-average beside it, altitude +
 * height-above-takeoff, ground speed + glide ratio (shown only when actually gliding — the K7
 * honesty rule), the live circling wind, and a source/battery status line. Sits in a map
 * corner, shown only while connected. Deliberately terse, high-contrast, readable at a glance.
 */
@Composable
fun VarioHud(deck: FlightDeckState, settings: SettingsState, modifier: Modifier = Modifier) {
    val prefs = UnitPrefs(
        temperature = settings.temperatureUnit,
        speed = settings.speedUnit,
        distance = settings.distanceUnit,
        altitude = settings.altitudeUnit,
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HUD_BG)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // ── Climb (big) + thermal average (the centering needle) ──
        val climb = deck.climbMs
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (climb == null) "— m/s" else "%+.1f m/s".format(climb),
                color = varioColor(climb),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            deck.avgClimbMs?.let {
                Text(
                    text = "ø %+.1f".format(it),
                    color = varioColor(it),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ── Altitude (MSL) + height-above-takeoff ──
        deck.altitudeM?.let { alt ->
            val height = deck.takeoffDatumM?.let { FlightMetrics.heightAboveTakeoff(alt, it) }
            val line = buildString {
                append(Units.altitude(alt, prefs.altitude))
                if (height != null) append("  ▲ ${Units.altitude(height, prefs.altitude)}")
            }
            Text(line, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }

        // ── Ground speed + glide ratio (L/D only when gliding) ──
        deck.groundSpeedMs?.let { gs ->
            val ld = climb?.let { FlightMetrics.glideRatio(gs, it) }
            val line = buildString {
                append("GS ${Units.speed(gs * MS_TO_KNOTS, prefs.speed)}")
                if (ld != null) append("  ·  L/D %.1f".format(ld))
            }
            Text(line, color = MUTED, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }

        // ── Live circling wind ──
        if (deck.windFromDeg != null && deck.windSpeedMs != null) {
            val oct = octantOf(deck.windFromDeg!!)
            val spd = Units.speed(deck.windSpeedMs!! * MS_TO_KNOTS, prefs.speed)
            Text(
                text = "wind ${deck.windFromDeg!!.toInt()}° $oct · $spd",
                color = Color(0xFF93C5FD),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // ── Source + battery ──
        val source = if (deck.positionSource == PositionSource.XC_TRACER) "◉ XC Tracer" else "◉ phone"
        val battery = deck.batteryPct?.let { "  🔋$it%" } ?: ""
        Text("$source$battery", color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun varioColor(ms: Double?): Color = when {
    ms == null -> Color.White
    ms > 0.1 -> Color(0xFF22C55E)
    ms < -0.1 -> Color(0xFFEF4444)
    else -> Color.White
}
