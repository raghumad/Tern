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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.ternparagliding.R
import com.ternparagliding.units.Units
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The deck's primary instrument — a full-width **altitude tape** with the **vario arc overlaid on
 * the left**. The vario is a curved wedge hugging the left edge: pinched thin at the centre (zero),
 * fanning **wide-green toward the top** (climb) and **wide-red toward the bottom** (sink). The faint
 * full arc is always visible; a solid fill rises/falls from the centre with the current climb. The
 * altitude scale runs full-width underneath — value labels + current-altitude box on the right with
 * a ft/m subscript, a blue ▸ launch reference — so the arc overlaps it without stealing width.
 *
 * Styled in Gruppo on the deck colour code; labels carry a shadow for contrast over any terrain.
 */
@Composable
fun AltitudeVarioTape(
    altitudeM: Double?,
    climbMs: Double?,
    avgClimbMs: Double?,
    takeoffDatumM: Double?,
    altitudeUnit: String,
    modifier: Modifier = Modifier,
    tapeHeight: Dp = 348.dp,
) {
    val cur = altitudeM ?: return
    val ctx = LocalContext.current
    val gruppo = remember { ResourcesCompat.getFont(ctx, R.font.gruppo_regular) }

    val varioUnit = if (altitudeUnit == "ft") "ft/min" else "m/s"
    val vMax = if (altitudeUnit == "ft") 1000.0 else 5.0
    val altStep = if (altitudeUnit == "ft") 200 else 50
    val altWindow = if (altitudeUnit == "ft") 600.0 else 175.0
    val altSym = Units.altitudeSymbol(altitudeUnit)
    val curDisp = Units.altitudeValue(cur, altitudeUnit)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DeckColors.panel(0.34f))
            .width(98.dp)
            .height(tapeHeight),
    ) {
        Canvas(Modifier.size(98.dp, tapeHeight)) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val varioH = h * 0.42f
            val baseW = 3.dp.toPx()       // arc width at the centre (thinnest)
            val maxW = w * 0.46f          // arc width at the extremes (overlaps the left ~46%)

            fun mkPaint(sizeSp: Float, c: Color, align: android.graphics.Paint.Align, shadow: Boolean = true) =
                android.graphics.Paint().apply {
                    isAntiAlias = true; color = c.toArgb(); textSize = sizeSp
                    typeface = gruppo; textAlign = align
                    if (shadow) setShadowLayer(4f, 1.2f, 1.2f, android.graphics.Color.BLACK)
                }
            fun text(s: String, x: Float, y: Float, p: android.graphics.Paint) =
                drawContext.canvas.nativeCanvas.drawText(s, x, y, p)

            fun altY(disp: Double) = cy - ((disp - curDisp) / altWindow).toFloat() * (h * 0.46f)
            // Left-anchored arc: width grows from centre to top/bottom (curved via power profile).
            fun archW(y: Float): Float {
                val t = (abs(y - cy) / varioH).coerceIn(0f, 1f)
                return baseW + (maxW - baseW) * t.pow(0.7f)
            }
            fun wedge(toY: Float): Path {
                val p = Path()
                p.moveTo(0f, cy)
                p.lineTo(0f, toY)                       // straight left edge (the screen edge)
                val steps = 16
                for (i in 0..steps) {                   // curved right boundary back to centre
                    val yy = toY + (cy - toY) * (i / steps.toFloat())
                    p.lineTo(archW(yy), yy)
                }
                p.close()
                return p
            }

            // ── Vario arc: faint full gauge (green up / red down), then the solid current fill ──
            drawPath(wedge(cy - varioH), DeckColors.lift.copy(alpha = 0.16f))
            drawPath(wedge(cy + varioH), DeckColors.sink.copy(alpha = 0.16f))
            val climb = climbMs ?: 0.0
            val vy = cy - (Units.varioValue(climb, varioUnit) / vMax).coerceIn(-1.0, 1.0).toFloat() * varioH
            drawPath(wedge(vy), (if (climb >= 0) DeckColors.lift else DeckColors.sink).copy(alpha = 0.72f))

            // Thermal-average tick (amber) across the arc.
            avgClimbMs?.let { avg ->
                val ay = cy - (Units.varioValue(avg, varioUnit) / vMax).coerceIn(-1.0, 1.0).toFloat() * varioH
                drawLine(DeckColors.attention, Offset(0f, ay), Offset(archW(ay), ay), strokeWidth = 2.5.dp.toPx())
            }

            // ── Altitude scale (right): ticks + value labels (bright + shadowed for contrast) ──
            val altLabel = mkPaint(10.sp.toPx(), Color(0xFFE8EAED), android.graphics.Paint.Align.RIGHT)
            var d = floor((curDisp - altWindow) / altStep).toInt() * altStep
            while (d <= curDisp + altWindow) {
                val ty = altY(d.toDouble())
                if (ty in 14f..(h - 4f) && abs(ty - cy) > 16.dp.toPx()) {
                    drawLine(Color(0xCCFFFFFF), Offset(w - w * 0.09f, ty), Offset(w, ty), strokeWidth = 1.dp.toPx())
                    text("$d", w - w * 0.11f, ty + 3.5.dp.toPx(), altLabel)
                }
                d += altStep
            }

            // ── Launch reference (blue ▸) ──
            takeoffDatumM?.let { datum ->
                val dDisp = Units.altitudeValue(datum, altitudeUnit).coerceIn(curDisp - altWindow, curDisp + altWindow)
                val ly = altY(dDisp)
                val s = 5.dp.toPx()
                val tri = Path().apply { moveTo(w * 0.62f + s, ly); lineTo(w * 0.62f - s, ly - s); lineTo(w * 0.62f - s, ly + s); close() }
                drawLine(DeckColors.reference.copy(alpha = 0.6f), Offset(w * 0.62f, ly), Offset(w, ly), strokeWidth = 1.2.dp.toPx())
                drawPath(tri, DeckColors.reference)
            }

            // ── Unit header (vario, top-left) ──
            text(varioUnit, 2.dp.toPx(), 12.dp.toPx(), mkPaint(8.sp.toPx(), DeckColors.unitColor, android.graphics.Paint.Align.LEFT))

            // A boxed readout with the unit as a *bottom-aligned* subscript: the number and the
            // smaller unit share one baseline, so the unit sits on the number's bottom (not floating).
            // Returns the box's left edge so callers can keep boxes from overlapping.
            fun valueBox(numStr: String, unit: String?, numColor: Color, numSp: Float, anchorRight: Boolean, maxRight: Float): Float {
                val numPaint = mkPaint(numSp, numColor, android.graphics.Paint.Align.LEFT, shadow = false)
                val uPaint = mkPaint(numSp * 0.6f, DeckColors.unitColor, android.graphics.Paint.Align.LEFT, shadow = false)
                val gap = if (unit != null) 2.dp.toPx() else 0f
                val numW = numPaint.measureText(numStr)
                val uW = unit?.let { uPaint.measureText(it) } ?: 0f
                val padX = 5.dp.toPx(); val boxH = 22.dp.toPx()
                val boxW = numW + gap + uW + 2 * padX
                val boxLeft = if (anchorRight) w - boxW else 0f
                if (!anchorRight && boxLeft + boxW > maxRight) return boxLeft // skip if it would collide
                val baseline = cy + numPaint.textSize * 0.35f
                drawRect(DeckColors.panel(0.95f), topLeft = Offset(boxLeft, cy - boxH / 2), size = Size(boxW, boxH))
                drawRect(numColor, topLeft = Offset(boxLeft, cy - boxH / 2), size = Size(boxW, boxH), style = Stroke(1.dp.toPx()))
                text(numStr, boxLeft + padX, baseline, numPaint)
                unit?.let { text(it, boxLeft + padX + numW + gap, baseline, uPaint) } // SAME baseline → bottom-aligned
                return boxLeft
            }
            fun varioNum(ms: Double): String =
                if (varioUnit == "ft/min") "%+d".format(Units.varioValue(ms, varioUnit).roundToInt())
                else "%+.1f".format(ms)

            // ── Current altitude (right) — number + ft/m subscript ──
            val altLeft = valueBox("${curDisp.roundToInt()}", altSym, DeckColors.primary, 16.sp.toPx(), anchorRight = true, maxRight = w)
            // ── Current climb (left, colour-coded) — the instantaneous vario number lives on the
            //    tape now that the HUD no longer repeats it. Kept clear of the altitude box.
            val vColor = when { climb > 0.1 -> DeckColors.lift; climb < -0.1 -> DeckColors.sink; else -> DeckColors.primary }
            valueBox(varioNum(climb), null, vColor, 15.sp.toPx(), anchorRight = false, maxRight = altLeft - 3.dp.toPx())
        }
    }
}
