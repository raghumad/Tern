package com.ternparagliding.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.ternparagliding.R
import com.ternparagliding.units.Units
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The deck's primary instrument — a combined altitude + vario tape with **two labeled scales**:
 *  - **right = altitude** (ft or m): scrolling scale with value labels, the current altitude
 *    boxed at the centre pointer, and a blue ▸ launch reference (gap = height-above-takeoff).
 *  - **left = vario** (ft/min or m/s): a fixed ±max scale with ±reference marks; the colored bar
 *    grows up-green / down-red from the centre, and an amber tick marks the thermal average.
 *
 * Both share the centre line (your current state). Styled in Gruppo, on the deck colour code,
 * translucent so the map reads through. The HUD still carries the precise digits.
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
    val cur = altitudeM ?: return
    val ctx = LocalContext.current
    val gruppo = remember { ResourcesCompat.getFont(ctx, R.font.gruppo_regular) }

    val varioUnit = if (altitudeUnit == "ft") "ft/min" else "m/s"
    val vMax = if (altitudeUnit == "ft") 1000.0 else 5.0
    val vMaxLabel = if (altitudeUnit == "ft") "1k" else "5"
    val altStep = if (altitudeUnit == "ft") 200 else 50
    val altWindow = if (altitudeUnit == "ft") 600.0 else 175.0
    val altSym = Units.altitudeSymbol(altitudeUnit)
    val curDisp = Units.altitudeValue(cur, altitudeUnit)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DeckColors.panel(0.34f))
            .width(110.dp)
            .height(348.dp),
    ) {
        Canvas(Modifier.size(110.dp, 348.dp)) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val split = w * 0.40f      // boundary: vario lane (left) | altitude scale (right)
            val varioH = h * 0.40f
            val altH = h * 0.46f

            fun mkPaint(sizeSp: Float, c: Color, align: android.graphics.Paint.Align) =
                android.graphics.Paint().apply {
                    isAntiAlias = true; color = c.toArgb(); textSize = sizeSp
                    typeface = gruppo; textAlign = align
                }
            fun text(s: String, x: Float, yBaseline: Float, p: android.graphics.Paint) =
                drawContext.canvas.nativeCanvas.drawText(s, x, yBaseline, p)

            fun varioY(disp: Double) = cy - (disp / vMax).coerceIn(-1.0, 1.0).toFloat() * varioH
            fun altY(disp: Double) = cy - ((disp - curDisp) / altWindow).toFloat() * altH

            // ── Altitude scale (right): ticks + value labels ──
            val altLabel = mkPaint(9.sp.toPx(), DeckColors.neutral, android.graphics.Paint.Align.RIGHT)
            var d = floor((curDisp - altWindow) / altStep).toInt() * altStep
            while (d <= curDisp + altWindow) {
                val ty = altY(d.toDouble())
                if (ty in 14f..(h - 4f)) {
                    drawLine(DeckColors.neutral.copy(alpha = 0.45f), Offset(w * 0.90f, ty), Offset(w, ty), strokeWidth = 1.dp.toPx())
                    if (abs(ty - cy) > 16.dp.toPx()) text("$d", w - w * 0.12f, ty + 3.dp.toPx(), altLabel)
                }
                d += altStep
            }

            // ── Launch reference (blue ▸) ──
            takeoffDatumM?.let { datum ->
                val dDisp = Units.altitudeValue(datum, altitudeUnit).coerceIn(curDisp - altWindow, curDisp + altWindow)
                val ly = altY(dDisp)
                val s = 5.dp.toPx()
                val tri = Path().apply { moveTo(split + s, ly); lineTo(split - s, ly - s); lineTo(split - s, ly + s); close() }
                drawLine(DeckColors.reference.copy(alpha = 0.55f), Offset(split, ly), Offset(w, ly), strokeWidth = 1.2.dp.toPx())
                drawPath(tri, DeckColors.reference)
            }

            // ── Vario scale (left): reference marks + ±max labels ──
            for (frac in listOf(1.0, 0.5, -0.5, -1.0)) {
                val vy = cy - frac.toFloat() * varioH
                drawLine(DeckColors.neutral.copy(alpha = 0.4f), Offset(0f, vy), Offset(split, vy), strokeWidth = 1.dp.toPx())
            }
            val vLabel = mkPaint(9.sp.toPx(), DeckColors.neutral, android.graphics.Paint.Align.LEFT)
            text("+$vMaxLabel", 2.dp.toPx(), cy - varioH + 9.dp.toPx(), vLabel)
            text("-$vMaxLabel", 2.dp.toPx(), cy + varioH - 1.dp.toPx(), vLabel)

            // ── Vario bar (left lane) ──
            val climb = climbMs ?: 0.0
            val vy = varioY(Units.varioValue(climb, varioUnit))
            val barColor = (if (climb >= 0) DeckColors.lift else DeckColors.sink).copy(alpha = 0.7f)
            val top = minOf(cy, vy); val bot = maxOf(cy, vy)
            if (bot - top > 0.5f) drawRect(barColor, topLeft = Offset(split * 0.30f, top), size = Size(split * 0.40f, bot - top))

            // ── Thermal-average tick (amber) ──
            avgClimbMs?.let { avg ->
                val ay = varioY(Units.varioValue(avg, varioUnit))
                drawLine(DeckColors.attention, Offset(split * 0.10f, ay), Offset(split * 0.90f, ay), strokeWidth = 2.5.dp.toPx())
            }

            // ── Unit headers (top of each scale) ──
            text(varioUnit, 2.dp.toPx(), 11.dp.toPx(), mkPaint(8.sp.toPx(), DeckColors.unitColor, android.graphics.Paint.Align.LEFT))
            text(altSym, w - 2.dp.toPx(), 11.dp.toPx(), mkPaint(8.sp.toPx(), DeckColors.unitColor, android.graphics.Paint.Align.RIGHT))

            // ── Centre reference line + current-altitude box ──
            drawLine(DeckColors.primary, Offset(split, cy), Offset(w, cy), strokeWidth = 2.dp.toPx())
            val curLabel = "${curDisp.roundToInt()}"
            val boxPaint = mkPaint(15.sp.toPx(), DeckColors.primary, android.graphics.Paint.Align.RIGHT)
            val padX = 5.dp.toPx(); val boxH = 22.dp.toPx()
            val textW = boxPaint.measureText(curLabel)
            val boxLeft = w - textW - 2 * padX
            drawRect(DeckColors.panel(0.92f), topLeft = Offset(boxLeft, cy - boxH / 2), size = Size(textW + 2 * padX, boxH))
            drawRect(DeckColors.primary, topLeft = Offset(boxLeft, cy - boxH / 2), size = Size(textW + 2 * padX, boxH), style = Stroke(1.dp.toPx()))
            text(curLabel, w - padX, cy + boxPaint.textSize * 0.35f, boxPaint)
        }
    }
}
