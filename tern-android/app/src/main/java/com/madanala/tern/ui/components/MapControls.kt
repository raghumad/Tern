package com.madanala.tern.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Compass(
    rotation: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val ringColor = MaterialTheme.colorScheme.outline
    val northColor = MaterialTheme.colorScheme.error

    Canvas(
        modifier = modifier
            .size(size) // Use the size parameter
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
    }
}
