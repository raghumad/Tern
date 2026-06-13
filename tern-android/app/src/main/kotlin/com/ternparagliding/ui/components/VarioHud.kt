package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.redux.FlightDeckState
import com.ternparagliding.redux.SettingsState
import com.ternparagliding.units.UnitPrefs
import com.ternparagliding.units.Units
import com.ternparagliding.weather.octantOf

private const val MS_TO_KNOTS = 1.943844
private val HUD_BG = Color(0xFF0F1117).copy(alpha = 0.62f)

/**
 * A glanceable flight-deck readout for the live external vario: climb (the number that
 * matters most, big + color-coded), altitude, and the live circling-wind estimate. Sits in a
 * map corner; shown only while the vario is connected. The first instrument of the deck —
 * deliberately terse, high-contrast, readable at a glance with hands busy.
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
        val climb = deck.climbMs
        val climbColor = when {
            climb == null -> Color.White
            climb > 0.1 -> Color(0xFF22C55E)
            climb < -0.1 -> Color(0xFFEF4444)
            else -> Color.White
        }
        Text(
            text = if (climb == null) "— m/s" else "%+.1f m/s".format(climb),
            color = climbColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        deck.altitudeM?.let {
            Text(
                text = Units.altitude(it, prefs.altitude),
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (deck.windFromDeg != null && deck.windSpeedMs != null) {
            val oct = octantOf(deck.windFromDeg!!)
            val spd = Units.speed(deck.windSpeedMs!! * MS_TO_KNOTS, prefs.speed)
            Row {
                Text(
                    text = "wind ${deck.windFromDeg!!.toInt()}° $oct · $spd",
                    color = Color(0xFF93C5FD),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
