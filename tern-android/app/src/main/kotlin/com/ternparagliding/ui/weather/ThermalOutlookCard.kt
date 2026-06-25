package com.ternparagliding.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ternparagliding.weather.ThermalOutlook
import com.ternparagliding.weather.ThermalQuality
import com.ternparagliding.utils.io.siteTimeZone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val NO_LIFT = Color(0xFF94A3B8)  // slate — below working strength
private val WORKING = Color(0xFF22C55E)  // green — usable lift
private val STRONG = Color(0xFFF97316)   // orange — strong/overdevelopment watch

/**
 * The "when today are the thermals working, and how strong" read — a numeric **w\*** climb-rate
 * outlook with the day's working window, the peak, and how high lift reaches. Sits under the
 * SoarableCard (when→flyable) as the XC pilot's timing read. UI only; the model is claim-tested
 * in [com.ternparagliding.weather.assessThermalDays].
 */
@Composable
fun ThermalOutlookCard(outlook: ThermalOutlook, modifier: Modifier = Modifier) {
    val tf = remember(outlook.utcOffsetSeconds) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = siteTimeZone(outlook.utcOffsetSeconds) }
    }
    val peak = outlook.peak

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val (headline, color) = when {
                outlook.windowStartMs != null && outlook.windowEndMs != null -> {
                    val peakSuffix = peak?.startMs?.let { " · peak ~${tf.format(Date(it))}" } ?: ""
                    "🔥 Thermals ${tf.format(Date(outlook.windowStartMs))}–${tf.format(Date(outlook.windowEndMs))}$peakSuffix" to WORKING
                }
                else -> "No usable thermals today" to NO_LIFT
            }
            Text(headline, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            // w* sparkline — bar per daylight hour, coloured by working strength.
            val wStars = outlook.hours.map { it.wStarMs }
            if (wStars.any { it != null }) {
                Spacer(Modifier.height(10.dp))
                val maxW = wStars.filterNotNull().maxOrNull() ?: 0.0
                val minW = wStars.filterNotNull().minOrNull() ?: 0.0
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        fmtMs(minW),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Canvas(
                        Modifier.weight(1f).height(40.dp).padding(horizontal = 8.dp),
                    ) {
                        val n = wStars.size
                        if (n == 0 || maxW <= 0.0) return@Canvas
                        val gap = 2.dp.toPx()
                        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                        wStars.forEachIndexed { i, ws ->
                            val v = ws ?: 0.0
                            val h = (v / maxW).toFloat() * size.height
                            val x = i * (barW + gap)
                            drawRect(
                                color = when {
                                    ws == null -> NO_LIFT.copy(alpha = 0.25f)
                                    ws >= 2.5 -> STRONG
                                    ws >= 1.0 -> WORKING
                                    else -> NO_LIFT
                                },
                                topLeft = Offset(x, size.height - h),
                                size = Size(barW, h),
                            )
                        }
                    }
                    Text(
                        "${fmtMs(maxW)} m/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // Best: peak climb + qualitative strength.
            peak?.let { p ->
                val climb = p.wStarMs?.let { "${fmtMs(it)} m/s" } ?: "—"
                val at = p.startMs.let { " @ ${tf.format(Date(it))}" }
                Text(
                    "Best  $climb$at · ${strengthLabel(p.strength)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Top: how high the thermals reach (parcel top).
            peak?.thermalTopMslM?.let { topMsl ->
                val agl = peak.thermalTopAglM?.let { " (≈${it.roundToInt()} AGL)" } ?: ""
                Text(
                    "Top   thermals top ~${topMsl.roundToInt()} m MSL$agl",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Base: cumulus-marked or blue.
            peak?.let { p ->
                val baseLine = p.cuBaseMslM?.let { "cumulus ~${it.roundToInt()} m MSL" } ?: "blue thermals (no cumulus)"
                Text("Base  $baseLine", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun fmtMs(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

private fun strengthLabel(q: ThermalQuality): String = when (q) {
    ThermalQuality.NONE -> "no lift"
    ThermalQuality.WEAK -> "weak"
    ThermalQuality.WORKABLE -> "workable"
    ThermalQuality.STRONG -> "strong"
}
