package com.ternparagliding.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.utils.io.ProfileLevel
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.dewpointC
import com.ternparagliding.weather.SoundingAnalysis
import com.ternparagliding.weather.analyseSounding

private val ENV_RED = Color(0xFFFF5252)      // environment temperature
private val DEW_BLUE = Color(0xFF40C4FF)     // dewpoint
private val PARCEL_AMBER = Color(0xFFFFB300) // rising parcel (dry adiabat)
private val CLOUD_CYAN = Color(0xFF00E5FF)
private const val DRY_ADIABAT = 0.0098       // °C/m

/**
 * A simplified **Skew-T / atmospheric profile** for one moment: environment temperature
 * (red) and dewpoint (blue) against height, the dry-adiabatic **parcel ascent** (amber
 * dashed) from the surface, and markers for **cloudbase** (where the parcel saturates)
 * and the **thermal top** (where it meets the environment). Height in km MSL, temp °C —
 * the conventional sounding axes. Drawn from the fetched pressure-level [WeatherData.profile].
 */
@Composable
fun SkewTPlot(
    weather: WeatherData,
    elevationM: Double?,
    modifier: Modifier = Modifier,
) {
    val profile = weather.profile?.sortedBy { it.heightM } ?: return
    if (profile.size < 2) return

    val surfaceH = elevationM ?: (profile.first().heightM - 100.0)
    val surfaceT = weather.temperature
    val surfaceTd = dewpointC(weather.temperature, weather.humidity)
    val sounding: SoundingAnalysis? = elevationM?.let { analyseSounding(surfaceT, surfaceTd, it, profile) }

    // Series surface-up (only levels at/above the surface).
    val above = profile.filter { it.heightM >= surfaceH }
    val envPts = listOf(surfaceT to surfaceH) + above.map { it.tempC to it.heightM }
    val dewPts = listOf(surfaceTd to surfaceH) + above.map { it.dewpointC to it.heightM }

    val hMin = surfaceH
    val hMax = (above.maxOfOrNull { it.heightM } ?: (surfaceH + 4000.0)).coerceAtLeast(hMin + 1000.0)
    val tMin = (dewPts.minOf { it.first } - 3.0)
    val tMax = (envPts.maxOf { it.first } + 3.0)

    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(top = 4.dp),
    ) {
        val padL = 38f; val padB = 22f; val padT = 8f; val padR = 8f
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB

        fun x(t: Double) = padL + ((t - tMin) / (tMax - tMin)).toFloat() * plotW
        fun y(h: Double) = padT + (1f - ((h - hMin) / (hMax - hMin)).toFloat()) * plotH

        val lp = android.graphics.Paint().apply {
            color = labelColor.toArgb(); textSize = 9.5f * density; isAntiAlias = true
        }

        // Height gridlines every 1000 m.
        var h = (Math.ceil(hMin / 1000.0) * 1000.0)
        while (h <= hMax) {
            val yy = y(h)
            drawLine(axisColor.copy(alpha = 0.18f), Offset(padL, yy), Offset(padL + plotW, yy), 1f)
            drawContext.canvas.nativeCanvas.drawText("${(h / 1000).toInt()}k", 2f, yy + 3.5f * density, lp)
            h += 1000.0
        }
        // Temperature gridlines every 10 °C.
        var t = Math.ceil(tMin / 10.0) * 10.0
        while (t <= tMax) {
            val xx = x(t)
            drawLine(axisColor.copy(alpha = 0.18f), Offset(xx, padT), Offset(xx, padT + plotH), 1f)
            drawContext.canvas.nativeCanvas.drawText("${t.toInt()}°", xx - 6f * density, size.height - 4f, lp)
            t += 10.0
        }

        // Cloudbase + thermal-top markers (under the curves).
        sounding?.cloudBaseMslM?.let { base ->
            if (base in hMin..hMax) {
                val yy = y(base)
                drawLine(CLOUD_CYAN.copy(alpha = 0.7f), Offset(padL, yy), Offset(padL + plotW, yy), 1.5f * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
        sounding?.thermalTopMslM?.let { top ->
            if (top in hMin..hMax) {
                val yy = y(top)
                drawLine(PARCEL_AMBER.copy(alpha = 0.6f), Offset(padL, yy), Offset(padL + plotW, yy), 1.5f * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }

        // Parcel ascent (dry adiabat) from the surface up to the thermal top (or chart top).
        val parcelTopH = sounding?.thermalTopMslM ?: hMax
        val pPts = buildList {
            var hh = surfaceH
            while (hh <= parcelTopH) { add((surfaceT - DRY_ADIABAT * (hh - surfaceH)) to hh); hh += 250.0 }
            add((surfaceT - DRY_ADIABAT * (parcelTopH - surfaceH)) to parcelTopH)
        }
        drawSeries(pPts, ::x, ::y, PARCEL_AMBER, 2f * density, dashed = true)

        // Environment temp + dewpoint.
        drawSeries(dewPts, ::x, ::y, DEW_BLUE, 2.5f * density)
        drawSeries(envPts, ::x, ::y, ENV_RED, 2.5f * density)
    }

    // Legend + key derived numbers.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(ENV_RED, "Temp")
        LegendDot(DEW_BLUE, "Dewpoint")
        LegendDot(PARCEL_AMBER, "Parcel")
    }
    sounding?.let { s ->
        Text(
            buildString {
                s.thermalTopAglM?.let { append("Thermals top ~${it.toInt()} m AGL") }
                append(if (s.cumulus) " · cumulus base ~${s.cloudBaseAglM.toInt()} m" else " · blue (base ~${s.cloudBaseAglM.toInt()} m)")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun DrawScope.drawSeries(
    pts: List<Pair<Double, Double>>,
    x: (Double) -> Float,
    y: (Double) -> Float,
    color: Color,
    width: Float,
    dashed: Boolean = false,
) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 7f)) else null
    for (i in 0 until pts.size - 1) {
        drawLine(
            color,
            Offset(x(pts[i].first), y(pts[i].second)),
            Offset(x(pts[i + 1].first), y(pts[i + 1].second)),
            strokeWidth = width,
            pathEffect = effect,
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f) }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
