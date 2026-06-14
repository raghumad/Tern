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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

private val TAPE_BG = Color(0xCC0F1117)
private val LIFT = Color(0xFF22C55E)
private val SINK = Color(0xFFEF4444)
private val TICK = Color(0x55FFFFFF)
private val ZERO_LINE = Color(0xCCFFFFFF)
private val NEEDLE = Color(0xFFFFFFFF)
private val AVG_MARK = Color(0xFFF59E0B)

private const val MAX_MS = 5.0 // tape spans ±5 m/s — beyond that it just pegs

/**
 * The analog vario tape from the deck mockup — a vertical bar pinned to the screen edge that
 * fills **up green** as you climb and **down red** as you sink, against a fixed ±5 m/s scale.
 * It's the at-a-glance "am I going up?" instrument that complements the precise number in the
 * [VarioHud]: the eye catches the bar moving long before it reads digits. A bright needle marks
 * the instant climb; an amber tick marks the thermal average (where to center).
 *
 * Pure/graphical — the bar is unitless (proportion of ±5 m/s), so it reads the same whatever
 * the pilot's unit setting; the HUD carries the number in m/s or ft/min.
 */
@Composable
fun VarioTape(climbMs: Double?, avgClimbMs: Double?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TAPE_BG)
            .width(34.dp)
            .height(320.dp),
    ) {
        Canvas(Modifier.size(34.dp, 320.dp)) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            fun yFor(v: Double): Float = (cy - (v.coerceIn(-MAX_MS, MAX_MS) / MAX_MS) * cy).toFloat()

            // Scale ticks every 1 m/s; the zero line is full-width and brighter.
            for (i in -5..5) {
                val ty = yFor(i.toDouble())
                if (i == 0) {
                    drawLine(ZERO_LINE, Offset(0f, ty), Offset(w, ty), strokeWidth = 2.dp.toPx())
                } else {
                    val len = if (i % 5 == 0) w * 0.55f else w * 0.35f
                    drawLine(TICK, Offset(0f, ty), Offset(len, ty), strokeWidth = 1.dp.toPx())
                }
            }

            // Fill from zero toward the current climb, coloured by direction.
            val climb = climbMs ?: 0.0
            val vy = yFor(climb)
            val fill = (if (climb >= 0) LIFT else SINK).copy(alpha = 0.55f)
            val top = minOf(cy, vy)
            val bottom = maxOf(cy, vy)
            if (bottom - top > 0.5f) {
                drawRect(fill, topLeft = Offset(0f, top), size = Size(w, bottom - top))
            }

            // Instant-climb needle.
            drawLine(NEEDLE, Offset(0f, vy), Offset(w, vy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)

            // Thermal-average marker — a small amber wedge pointing in from the right edge.
            avgClimbMs?.let { avg ->
                val ay = yFor(avg)
                val s = 6.dp.toPx()
                val wedge = Path().apply {
                    moveTo(w, ay - s)
                    lineTo(w - s, ay)
                    lineTo(w, ay + s)
                    close()
                }
                drawPath(wedge, AVG_MARK)
            }
        }
    }
}
