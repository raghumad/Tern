package com.ternparagliding.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Own-ship palette — a paraglider seen from above: cyan canopy, light risers, amber pilot.
private val WING = Color(0xFF22D3EE)
private val WING_CASING = Color(0xE60A1417)
private val RISER = Color(0xCCE5E7EB)
private val PILOT = Color(0xFFF59E0B)
private val HALO = Color(0x44000000)

/**
 * The pilot's own-ship marker — a top-view paraglider (canopy arc + risers + pilot body),
 * deliberately more legible than the generic vario triangle. It pivots about the **pilot**
 * (canvas centre), which is where the follow-camera keeps own-position, so it sits glued under
 * you. [headingDeg] is the track *relative to the map* (course − map bearing): in track-up glide
 * that's ~0 so the wing points up the screen; while circling (map heading held) it swings through
 * the turn, showing your bank in real time.
 */
@Composable
fun PilotGlyph(headingDeg: Float, modifier: Modifier = Modifier, size: Dp = 60.dp) {
    Canvas(modifier = modifier.size(size).rotate(headingDeg)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val pilotY = h * 0.50f // == canvas centre → rotation pivots about the pilot

        // Soft halo for contrast over busy terrain.
        drawCircle(HALO, radius = w * 0.16f, center = Offset(cx, pilotY))

        // Canopy: a shallow dome (leading edge convex forward/up). Compose angles: 270° = up.
        val rx = w * 0.40f
        val ry = h * 0.15f
        val ecy = h * 0.30f // canopy centre, ahead of the pilot
        val rect = Rect(Offset(cx - rx, ecy - ry), Size(rx * 2f, ry * 2f))
        val startDeg = 200f
        val sweepDeg = 140f
        drawArc(WING_CASING, startDeg, sweepDeg, useCenter = false, topLeft = rect.topLeft, size = rect.size, style = Stroke(h * 0.13f, cap = StrokeCap.Round))
        drawArc(WING, startDeg, sweepDeg, useCenter = false, topLeft = rect.topLeft, size = rect.size, style = Stroke(h * 0.085f, cap = StrokeCap.Round))

        // Risers: from the two canopy tips down to the pilot.
        fun tip(angleDeg: Float): Offset {
            val a = Math.toRadians(angleDeg.toDouble())
            return Offset(cx + rx * cos(a).toFloat(), ecy + ry * sin(a).toFloat())
        }
        val pilot = Offset(cx, pilotY)
        drawLine(RISER, tip(startDeg), pilot, strokeWidth = h * 0.03f, cap = StrokeCap.Round)
        drawLine(RISER, tip(startDeg + sweepDeg), pilot, strokeWidth = h * 0.03f, cap = StrokeCap.Round)

        // Pilot harness — a small forward-pointing wedge so the direction reads at a glance.
        val body = Path().apply {
            moveTo(cx, pilotY - h * 0.10f)            // nose (forward)
            lineTo(cx - w * 0.06f, pilotY + h * 0.06f)
            lineTo(cx + w * 0.06f, pilotY + h * 0.06f)
            close()
        }
        drawPath(body, WING_CASING)
        drawCircle(PILOT, radius = w * 0.045f, center = pilot)
    }
}
