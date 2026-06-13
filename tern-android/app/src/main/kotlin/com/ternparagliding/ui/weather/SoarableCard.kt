package com.ternparagliding.ui.weather

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ternparagliding.units.UnitPrefs
import com.ternparagliding.units.Units
import com.ternparagliding.weather.DayDigest
import com.ternparagliding.weather.Precip
import com.ternparagliding.weather.Sky
import com.ternparagliding.weather.SoarableDay
import com.ternparagliding.weather.Verdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The "when today is it flyable" read — the soarable window + a plain daily digest,
 * from [com.ternparagliding.weather.assessSoarableDays]. Sits under the now-verdict
 * (FlyabilityCard): "is it on now" then "when is it on today". Values in the pilot's
 * units. UI only — the logic is claim-tested in Soarable.kt.
 */
@Composable
fun SoarableCard(day: SoarableDay, units: UnitPrefs, modifier: Modifier = Modifier) {
    // Window timestamps are local-wall-clock carried as UTC epoch ms (Open-Meteo
    // timezone=auto), so format them in UTC to read back the local wall-clock time.
    val tf = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val best = day.best
            val (headline, color) = when {
                best == null -> "Not soarable today" to Color(0xFFEF4444)
                best.verdict == Verdict.GO ->
                    "Soarable ${tf.format(Date(best.startMs))}–${tf.format(Date(best.endMs))}" to Color(0xFF22C55E)
                else ->
                    "Marginal window ${tf.format(Date(best.startMs))}–${tf.format(Date(best.endMs))}" to Color(0xFFF59E0B)
            }
            Text(
                headline,
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )

            day.digest?.let { d ->
                Spacer(Modifier.height(6.dp))
                Text(windLine(d, units), style = MaterialTheme.typography.bodyMedium)
                Text(skyLine(d, units), style = MaterialTheme.typography.bodyMedium)
            }

            if (day.sunriseMs != null && day.sunsetMs != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sun ${tf.format(Date(day.sunriseMs))}–${tf.format(Date(day.sunsetMs))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Gusting to 13 kn · mostly NE — on direction" */
private fun windLine(d: DayDigest, units: UnitPrefs): String {
    val gust = "Gusting to ${Units.speed(d.maxGustKt, units.speed)}"
    val dir = d.dominantOctant?.name?.let { " · mostly $it" } ?: ""
    val onDir = when (d.onDirection) {
        true -> " — on direction"
        false -> " — cross/off direction"
        null -> ""
    }
    return "$gust$dir$onDir"
}

/** "9–23°C · mostly clear · dry" */
private fun skyLine(d: DayDigest, units: UnitPrefs): String {
    val lo = Units.tempValue(d.tempMinC, units.temperature).roundToInt()
    val hi = Units.temp(d.tempMaxC, units.temperature)
    return "$lo–$hi · ${skyLabel(d.sky)} · ${precipLabel(d.precip)}"
}

private fun skyLabel(sky: Sky): String = when (sky) {
    Sky.CLEAR -> "mostly clear"
    Sky.PARTLY_CLOUDY -> "partly cloudy"
    Sky.CLOUDY -> "cloudy"
    Sky.OVERCAST -> "overcast"
}

private fun precipLabel(p: Precip): String = when (p) {
    Precip.DRY -> "dry"
    Precip.LIGHT -> "light rain"
    Precip.WET -> "rain"
}
