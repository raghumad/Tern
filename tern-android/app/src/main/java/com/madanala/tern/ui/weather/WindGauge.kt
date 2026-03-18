package com.madanala.tern.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

@Composable
fun WindGauge(
    value: Double,
    maxValue: Double = 50.0,
    label: String,
    unit: String,
    directionDegrees: Double? = null,
    gradientColors: List<Color> = listOf(Color.Blue, Color.Cyan),
    modifier: Modifier = Modifier,
    size: Dp = 100.dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
            val arrowColor = MaterialTheme.colorScheme.onSurface
            Canvas(modifier = Modifier.size(size)) {
                val strokeWidth = 8.dp.toPx()
                val radius = (size.toPx() - strokeWidth) / 2
                val center = Offset(size.toPx() / 2, size.toPx() / 2)
                
                // Background Arc
                drawArc(
                    color = Color.Gray.copy(alpha = 0.3f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Value Arc
                val sweepAngle = 270f * (value / maxValue).coerceIn(0.0, 1.0).toFloat()
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = gradientColors,
                        center = center
                    ),
                    startAngle = 135f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Direction Arrow (if provided)
                if (directionDegrees != null) {
                    val arrowLength = radius * 0.8f
                    rotate(degrees = directionDegrees.toFloat() - 90f, pivot = center) {
                        // Draw simple triangle arrow
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(center.x, center.y - arrowLength) // Tip
                            lineTo(center.x - 10f, center.y - arrowLength + 20f)
                            lineTo(center.x + 10f, center.y - arrowLength + 20f)
                            close()
                        }
                        drawPath(path, arrowColor)
                    }
                }
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${value.roundToInt()}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("WindGaugeValue")
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
