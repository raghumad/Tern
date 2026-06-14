package com.ternparagliding.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.units.Units

private val TAPE_BG = Color(0xCC0F1117)
private val LIFT = Color(0xFF22C55E)
private val SINK = Color(0xFFEF4444)
private val TICK = Color(0x55FFFFFF)
private val CENTER_LINE = Color(0xFFFFFFFF)
private val AVG_MARK = Color(0xFFF59E0B)
private val LAUNCH = Color(0xFF93C5FD)

private const val WINDOW_M = 175.0    // visible altitude span = ±175 m around the pilot
private const val TREND_SEC = 30.0    // vario bar = where you'll be in 30 s, on the same scale
private const val TICK_STEP_M = 50

/**
 * The deck's primary instrument: a **combined altitude + vario tape** in the EFIS idiom. The
 * altitude scale scrolls past a fixed centre pointer (your current altitude, boxed); the vario
 * is the coloured **trend bar** extending up-green / down-red *from* that pointer to where you'll
 * be in [TREND_SEC] seconds — drawn on the *same* altitude scale, so rate and position read as
 * one motion. A blue ▸ marks launch, so height-above-takeoff is just the gap down to it; an amber
 * tick marks the thermal average. One glance answers "how high, going up or down how fast, and
 * how far above launch" without a single subtraction.
 *
 * Pure/graphical; the HUD still carries the precise numbers. Cloudbase/airspace marks can hang on
 * this same scale later (the gaps would read as "room to climb" / "clearance").
 */
@Composable
fun AltitudeVarioTape(
    altitudeM: Double?,
    climbMs: Double?,
    avgClimbMs: Double?,
    takeoffDatumM: Double?,
    altitudeUnit: String,
    modifier: Modifier = Modifier,
) {
    val cur = altitudeM ?: return // nothing meaningful before the first fix

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TAPE_BG)
            .width(96.dp)
            .height(340.dp),
    ) {
        Canvas(Modifier.size(96.dp, 340.dp)) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val laneW = w * 0.34f // left lane for the vario trend bar
            val pxPerM = (h / (2.0 * WINDOW_M)).toFloat()
            fun altToY(a: Double): Float = cy - ((a - cur) * pxPerM).toFloat()
            fun clampWin(a: Double): Double = a.coerceIn(cur - WINDOW_M, cur + WINDOW_M)

            // ── Altitude scale: minor ticks every 50 m, scrolling on the right ──
            var m = (Math.floor((cur - WINDOW_M) / TICK_STEP_M).toInt()) * TICK_STEP_M
            while (m <= cur + WINDOW_M) {
                val ty = altToY(m.toDouble())
                if (ty in 0f..h) {
                    val major = m % 100 == 0
                    val len = if (major) w * 0.30f else w * 0.18f
                    drawLine(TICK, Offset(w - len, ty), Offset(w, ty), strokeWidth = 1.dp.toPx())
                }
                m += TICK_STEP_M
            }

            // ── Launch reference: ▸ at the takeoff datum (clamped to the edge if off-scale) ──
            takeoffDatumM?.let { datum ->
                val ly = altToY(clampWin(datum))
                val s = 5.dp.toPx()
                val tri = Path().apply {
                    moveTo(laneW + s, ly); lineTo(laneW - s, ly - s); lineTo(laneW - s, ly + s); close()
                }
                drawLine(LAUNCH.copy(alpha = 0.6f), Offset(laneW + s, ly), Offset(w, ly), strokeWidth = 1.5.dp.toPx())
                drawPath(tri, LAUNCH)
            }

            // ── Vario trend bar: from the pointer to the 30 s-projected altitude ──
            val climb = climbMs ?: 0.0
            val tipY = altToY(clampWin(cur + climb * TREND_SEC))
            val barColor = (if (climb >= 0) LIFT else SINK).copy(alpha = 0.65f)
            val top = minOf(cy, tipY)
            val bot = maxOf(cy, tipY)
            if (bot - top > 0.5f) {
                drawRect(barColor, topLeft = Offset(laneW * 0.15f, top), size = Size(laneW * 0.70f, bot - top))
            }

            // ── Thermal-average tick (amber) on the same projection ──
            avgClimbMs?.let { avg ->
                val ay = altToY(clampWin(cur + avg * TREND_SEC))
                drawLine(AVG_MARK, Offset(laneW * 0.02f, ay), Offset(laneW * 0.98f, ay), strokeWidth = 2.5.dp.toPx())
            }

            // ── Centre reference line + current-altitude box ──
            drawLine(CENTER_LINE, Offset(laneW, cy), Offset(w, cy), strokeWidth = 2.dp.toPx())

            val label = Units.altitude(cur, altitudeUnit)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
                textSize = 14.sp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            val padX = 5.dp.toPx()
            val boxH = 22.dp.toPx()
            val textW = paint.measureText(label)
            val boxLeft = w - textW - 2 * padX
            drawRect(Color(0xF20B0E14), topLeft = Offset(boxLeft, cy - boxH / 2), size = Size(textW + 2 * padX, boxH))
            drawRect(CENTER_LINE, topLeft = Offset(boxLeft, cy - boxH / 2), size = Size(textW + 2 * padX, boxH), style = Stroke(1.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(label, w - padX, cy + paint.textSize * 0.35f, paint)
        }
    }
}
