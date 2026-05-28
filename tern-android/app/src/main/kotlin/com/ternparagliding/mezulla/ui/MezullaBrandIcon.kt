package com.ternparagliding.mezulla.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mezulla brand mark — a bold geometric M with two concentric radio-wave
 * arcs at the top-right, suggesting outgoing/incoming transmission.
 *
 * Same visual idiom as the wifi/cellular signal icons pilots already
 * recognize, so "M + waves" reads as "Mezulla mesh radio" at a glance.
 *
 * Defaults to white on a transparent background; the caller is expected
 * to provide the contrasting backing (a dark pill, a shadow, etc).
 */
@Composable
fun MezullaBrandIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = Color.White,
    strokeWidthDp: Dp = 2.5.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = strokeWidthDp.toPx()

        // M occupies the bottom-left ~60% of the canvas; radio waves
        // live in the top-right and MUST stay within the canvas bounds
        // (previous version had arcCenter+outerR > w, clipping the wave).
        val mLeft = w * 0.10f
        val mRight = w * 0.55f
        val mTop = h * 0.40f
        val mBottom = h * 0.92f
        val mMid = (mLeft + mRight) / 2f
        val mMidDip = mTop + (mBottom - mTop) * 0.55f  // valley between humps

        // M path: bottom-left → top-left → mid-valley → top-right → bottom-right
        val mPath = Path().apply {
            moveTo(mLeft, mBottom)
            lineTo(mLeft, mTop)
            lineTo(mMid, mMidDip)
            lineTo(mRight, mTop)
            lineTo(mRight, mBottom)
        }
        drawPath(
            path = mPath,
            color = color,
            style = Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )

        // Two radio-wave arcs in the top-right corner, anchored at the
        // right peak of the M. Radii kept conservative so outerR doesn't
        // exceed the remaining canvas width to the right of arcCenter.
        val arcCenter = Offset(mRight, mTop)
        val available = (w - arcCenter.x) - sw  // leave a stroke-width margin
        val outerR = available * 0.95f
        val innerR = outerR * 0.6f
        // Arc spans -75° to -15° (top-right quadrant), where 0° = east.
        val startAngle = -75f
        val sweepAngle = 60f
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(arcCenter.x - innerR, arcCenter.y - innerR),
            size = Size(innerR * 2f, innerR * 2f),
            style = Stroke(width = sw * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(arcCenter.x - outerR, arcCenter.y - outerR),
            size = Size(outerR * 2f, outerR * 2f),
            style = Stroke(width = sw * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )

        // Suppress unused-import lint
        @Suppress("UNUSED_VARIABLE") val _r: Rect? = null
        @Suppress("UNUSED_VARIABLE") val _p: PathEffect? = null
    }
}
