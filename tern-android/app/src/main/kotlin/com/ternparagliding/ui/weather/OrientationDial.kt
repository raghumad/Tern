package com.ternparagliding.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.units.UnitPrefs
import com.ternparagliding.units.Units
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.weather.Octant
import com.ternparagliding.weather.SiteContext
import com.ternparagliding.weather.octantOf
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val IDEAL = Color(0xFF22C55E)   // score 2
private val WORKABLE = Color(0xFFF59E0B) // score 1
private const val ORIENTATION_MIN_WIND_KT = 3.0 // mirrors FlyabilityLimits

/**
 * Wind-vs-launch at a glance: a compass ring with each octant tinted by the site's
 * flyability (green = ideal, amber = workable, faint = no), and an arrow streaming in
 * from the wind's **from** side, pointing downwind (a west wind comes in from the left,
 * arrow east). Its tail sits in the from-sector — green tail → on the hill. This is the
 * gauge re-pointed at the question that matters — not a speed dial. Pairs the PGE
 * orientation data with the live wind, the same join the Flyability verdict makes.
 */
@Composable
fun OrientationDial(
    orientations: Map<Octant, Int>,
    windFromDeg: Double,
    windSpeedKt: Double,
    modifier: Modifier = Modifier,
    diameter: Dp = 132.dp,
) {
    val measurer = rememberTextMeasurer()
    val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needleColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = remember { TextStyle(fontSize = 10.sp) }

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(diameter)) {
        Canvas(Modifier.size(diameter)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            val ringMid = r * 0.66f
            val ringThickness = r * 0.24f

            // Octant sectors, each 45° centered on its bearing, tinted by score.
            for (oct in Octant.entries) {
                val color = when (orientations[oct] ?: 0) {
                    2 -> IDEAL
                    1 -> WORKABLE
                    else -> faint
                }
                val bearing = oct.ordinal * 45f
                drawArc(
                    color = color,
                    startAngle = bearing - 90f - 21.5f, // canvas 0°=east; compass→canvas = bearing-90
                    sweepAngle = 43f,                    // 2° gaps between sectors
                    useCenter = false,
                    topLeft = Offset(c.x - ringMid, c.y - ringMid),
                    size = Size(ringMid * 2, ringMid * 2),
                    style = Stroke(width = ringThickness, cap = StrokeCap.Butt),
                )
            }

            // Cardinal labels just outside the ring.
            for ((letter, bearing) in listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)) {
                val rad = Math.toRadians((bearing - 90f).toDouble())
                val pos = Offset(c.x + (r * 0.92f) * cos(rad).toFloat(), c.y + (r * 0.92f) * sin(rad).toFloat())
                val lay = measurer.measure(letter, labelStyle.copy(color = labelColor))
                drawText(lay, topLeft = Offset(pos.x - lay.size.width / 2f, pos.y - lay.size.height / 2f))
            }

            // Incoming-wind arrow: it streams IN from the wind's from-side and points
            // downwind (a west wind comes in from the left, arrow east). The tail sits in
            // the from-sector (what the verdict reads); wind barbs ride the tail (knots).
            val nrad = Math.toRadians((windFromDeg - 90f))
            val ux = cos(nrad).toFloat()
            val uy = sin(nrad).toFloat()
            val tailR = ringMid - ringThickness / 2f - 5.dp.toPx() // rim, from-side
            val headR = r * 0.12f                                  // near centre, downwind
            val tail = Offset(c.x + tailR * ux, c.y + tailR * uy)
            val tip = Offset(c.x + headR * ux, c.y + headR * uy)
            val sw = 2.5.dp.toPx()
            drawLine(needleColor, tail, tip, strokeWidth = sw, cap = StrokeCap.Round)
            // Arrowhead at the tip, barbs opening back toward the tail.
            val headLen = 7.dp.toPx()
            for (off in listOf(-0.4, 0.4)) {
                val a = nrad + off
                drawLine(
                    needleColor, tip,
                    Offset(tip.x + headLen * cos(a).toFloat(), tip.y + headLen * sin(a).toFloat()),
                    strokeWidth = sw, cap = StrokeCap.Round,
                )
            }

            // Wind barbs at the tail (knots — the standard read: full = 10, half = 5,
            // pennant = 50). They stack from the tail inward along the shaft.
            val s = Offset(-ux, -uy) // tail → tip (downwind) unit vector
            fun rot(v: Offset, deg: Double): Offset {
                val a = Math.toRadians(deg); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                return Offset(v.x * ca - v.y * sa, v.x * sa + v.y * ca)
            }
            val barbDir = rot(s, 125.0) // back-and-to-one-side
            val fullLen = 9.dp.toPx(); val halfLen = 4.5.dp.toPx(); val stepPx = 5.dp.toPx()
            val barbSw = sw * 0.85f
            var spd = (windSpeedKt / 5.0).roundToInt() * 5
            val pennants = spd / 50; spd -= pennants * 50
            val fulls = spd / 10; spd -= fulls * 10
            val halves = spd / 5
            var d = stepPx * 0.6f
            repeat(pennants) {
                val b0 = tail + s * d; val b1 = tail + s * (d + stepPx * 1.1f); val o = b0 + barbDir * fullLen
                drawPath(Path().apply { moveTo(b0.x, b0.y); lineTo(b1.x, b1.y); lineTo(o.x, o.y); close() }, needleColor)
                d += stepPx * 1.4f
            }
            repeat(fulls) {
                val b = tail + s * d
                drawLine(needleColor, b, b + barbDir * fullLen, strokeWidth = barbSw, cap = StrokeCap.Round)
                d += stepPx
            }
            repeat(halves) {
                val b = tail + s * d
                drawLine(needleColor, b, b + barbDir * halfLen, strokeWidth = barbSw, cap = StrokeCap.Round)
                d += stepPx
            }
        }
    }
}

/**
 * The dial in a card, with the launch's flyable directions named and an on/off verdict
 * for the current wind — the "Flies: NE, E, SE (marginal: S)" + "on the hill" read.
 */
@Composable
fun OrientationCard(site: SiteContext, weather: WeatherData, units: UnitPrefs, modifier: Modifier = Modifier) {
    val windFrom = weather.windDirection10m ?: weather.wind.direction
    val windSpeedKt = weather.windSpeed10m ?: weather.wind.speed
    val from = octantOf(windFrom)
    val score = site.orientations[from] ?: 0

    val (verdict, color) = when {
        !site.hasOrientation -> "Launch orientation unknown" to MaterialTheme.colorScheme.onSurfaceVariant
        windSpeedKt < ORIENTATION_MIN_WIND_KT -> "Light wind — direction moot" to MaterialTheme.colorScheme.onSurfaceVariant
        score >= 2 -> "Wind on the hill" to IDEAL
        score == 1 -> "Workable — not straight on" to WORKABLE
        else -> "Cross / off direction" to Color(0xFFEF4444)
    }

    val good = Octant.entries.filter { (site.orientations[it] ?: 0) >= 2 }.joinToString(", ") { it.name }
    val marginal = Octant.entries.filter { site.orientations[it] == 1 }.joinToString(", ") { it.name }

    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrientationDial(
                orientations = site.orientations,
                windFromDeg = windFrom,
                windSpeedKt = windSpeedKt,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Launch orientation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(verdict, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                if (site.hasOrientation) {
                    if (good.isNotEmpty()) {
                        Text("Flies: $good", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (marginal.isNotEmpty()) {
                        Text("Marginal: $marginal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        "Not recorded on Paragliding Earth — check the slope yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${Units.speed(windSpeedKt, units.speed)} from ${windFrom.toInt()}° ($from)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
