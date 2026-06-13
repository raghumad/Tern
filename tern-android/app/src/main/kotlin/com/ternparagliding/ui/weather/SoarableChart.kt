package com.ternparagliding.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ternparagliding.units.UnitPrefs
import com.ternparagliding.units.Units
import com.ternparagliding.utils.io.ForecastPeriod
import com.ternparagliding.weather.SoarableDay
import com.ternparagliding.weather.Verdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val TEMP_COLOR = Color(0xFFF97316) // orange
private val WIND_COLOR = Color(0xFF38BDF8) // sky blue
private val GO_SHADE = Color(0xFF22C55E)
private val CAUTION_SHADE = Color(0xFFF59E0B)

/**
 * The 24–48 h forecast at a glance — temperature and wind over time, a wind-direction
 * arrow sparkline along the bottom, and the **soarable windows shaded green** (amber
 * for marginal). The shading is the point: it answers "when can I fly today?" without
 * reading numbers. Spedmo-style; the shading data comes from the soarable-window brain.
 * UI only. Two value scales (temp left, wind right) share the plot, like the original.
 */
@Composable
fun SoarableChart(
    hourly: List<ForecastPeriod>,
    days: List<SoarableDay>,
    units: UnitPrefs,
    modifier: Modifier = Modifier,
) {
    val pts = remember(hourly) { hourly.take(48) }
    if (pts.size < 2) return

    val tf = remember {
        SimpleDateFormat("EEE HH:00", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    val tMin = pts.first().startTime
    val tMax = pts.last().startTime
    val span = (tMax - tMin).coerceAtLeast(1L).toFloat()

    // Plot in the pilot's units so a future numeric axis stays consistent.
    val temps = pts.map { Units.tempValue(it.weather.temperature, units.temperature) }
    val winds = pts.map { Units.speedValue(it.weather.wind.speed, units.speed) }
    val tLo = temps.min()
    val tHi = temps.max().coerceAtLeast(tLo + 1.0)
    val wHi = winds.max().coerceAtLeast(1.0)

    val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)

    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            LegendDot(TEMP_COLOR, "temp")
            LegendDot(WIND_COLOR, "wind")
            LegendDot(GO_SHADE.copy(alpha = 0.5f), "soarable")
        }

        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val arrowBand = 22.dp.toPx()
            val plotTop = 6.dp.toPx()
            val plotBottom = size.height - arrowBand
            val plotH = plotBottom - plotTop
            val w = size.width

            fun xOf(ts: Long) = ((ts - tMin) / span) * w
            fun yTemp(v: Double) = plotBottom - ((v - tLo) / (tHi - tLo)).toFloat() * plotH
            fun yWind(v: Double) = plotBottom - (v / wHi).toFloat() * plotH

            // 1) Soarable shading — vertical green (GO) / amber (marginal) bands.
            for (day in days) for (win in day.windows) {
                val x0 = xOf(win.startMs).coerceIn(0f, w)
                val x1 = xOf(win.endMs).coerceIn(0f, w)
                if (x1 > x0) drawRect(
                    color = (if (win.verdict == Verdict.GO) GO_SHADE else CAUTION_SHADE).copy(alpha = 0.16f),
                    topLeft = Offset(x0, plotTop),
                    size = Size(x1 - x0, plotH),
                )
            }

            // 2) Day boundaries — faint vertical gridlines at local midnight.
            run {
                val dayMs = 86_400_000L
                var d = ((tMin / dayMs) + 1) * dayMs
                while (d < tMax) {
                    val x = xOf(d)
                    drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
                    d += dayMs
                }
            }

            // 3) Temperature + wind lines.
            drawPath(
                linePath(pts.indices.map { Offset(xOf(pts[it].startTime), yTemp(temps[it])) }),
                color = TEMP_COLOR, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                linePath(pts.indices.map { Offset(xOf(pts[it].startTime), yWind(winds[it])) }),
                color = WIND_COLOR, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // 4) Wind-direction arrows (every 3 h), pointing downwind.
            val ay = size.height - arrowBand / 2f
            val len = 10.dp.toPx()
            val sw = 1.5.dp.toPx()
            for (i in pts.indices step 3) {
                val cx = xOf(pts[i].startTime)
                val dir = (pts[i].weather.windDirection10m ?: pts[i].weather.wind.direction).toFloat()
                rotate(degrees = dir + 180f, pivot = Offset(cx, ay)) {
                    drawLine(arrowColor, Offset(cx, ay + len / 2f), Offset(cx, ay - len / 2f), strokeWidth = sw, cap = StrokeCap.Round)
                    drawLine(arrowColor, Offset(cx, ay - len / 2f), Offset(cx - len * 0.28f, ay - len * 0.12f), strokeWidth = sw, cap = StrokeCap.Round)
                    drawLine(arrowColor, Offset(cx, ay - len / 2f), Offset(cx + len * 0.28f, ay - len * 0.12f), strokeWidth = sw, cap = StrokeCap.Round)
                }
            }
        }

        // Time axis: 5 evenly spaced labels bracketing the range.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val fractions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            fractions.forEachIndexed { idx, f ->
                val ts = tMin + (span * f).toLong()
                Text(
                    text = tf.format(Date(ts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (idx) {
                        0 -> TextAlign.Start
                        fractions.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun linePath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
}
