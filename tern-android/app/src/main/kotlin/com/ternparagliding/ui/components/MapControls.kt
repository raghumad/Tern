package com.ternparagliding.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Live-wind needle on the rosette — sky blue, distinct from the red north carat.
private val WIND_COLOR = Color(0xFF38BDF8)

@Composable
fun Compass(
    rotation: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    /** Live wind direction (degrees the wind blows *from*); null hides the needle. */
    windFromDeg: Double? = null,
    /** Tap-to-north: reset the map to north-up. Null = not tappable. */
    onTap: (() -> Unit)? = null,
) {
    val ringColor = MaterialTheme.colorScheme.outline
    // Standard compass convention: north carat is bright red.
    val northColor = Color(0xFFFF1A1A)

    val tapModifier = if (onTap != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null, // no ripple on the transparent rosette
            onClickLabel = "Reset to north",
            onClick = onTap,
        )
    } else Modifier

    Canvas(
        modifier = modifier
            .size(size) // Use the size parameter
            .then(tapModifier)
            .rotate(rotation)
    ) {
        val center = this.center
        val radius = this.size.minDimension / 2.0f

        // Draw the outer circle
        drawCircle(
            color = ringColor,
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )

        // Define the path for the north-pointing carat (triangle)
        val path = Path().apply {
            val baseY = center.y - radius * 0.4f
            moveTo(center.x, center.y - radius * 0.8f) // Top point
            lineTo(center.x - radius * 0.2f, baseY) // Bottom-left point
            lineTo(center.x + radius * 0.2f, baseY) // Bottom-right point
            close() // Completes the triangle
        }

        // Draw the carat
        drawPath(
            path = path,
            color = northColor
        )

        // Live-wind needle: streams IN from the wind's from-side (tail at the rim) and points
        // downwind toward the centre. The canvas is already rotated so up = north, so the wind's
        // compass bearing maps straight to a clockwise-from-up angle.
        if (windFromDeg != null) {
            val th = Math.toRadians(windFromDeg)
            val dx = sin(th).toFloat()       // toward the from-side (north = up)
            val dy = (-cos(th)).toFloat()
            val tail = Offset(center.x + radius * 0.80f * dx, center.y + radius * 0.80f * dy)
            val tip = Offset(center.x - radius * 0.45f * dx, center.y - radius * 0.45f * dy)
            val stroke = 3.dp.toPx()
            drawLine(WIND_COLOR, tail, tip, strokeWidth = stroke, cap = StrokeCap.Round)
            // Arrowhead at the downwind tip.
            val phi = atan2((tip.y - tail.y), (tip.x - tail.x))
            val headLen = radius * 0.30f
            for (s in intArrayOf(-1, 1)) {
                val a = phi + Math.PI + s * 0.4
                drawLine(
                    WIND_COLOR, tip,
                    Offset(tip.x + headLen * cos(a).toFloat(), tip.y + headLen * sin(a).toFloat()),
                    strokeWidth = stroke, cap = StrokeCap.Round,
                )
            }
        }
    }
}
