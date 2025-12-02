package com.madanala.tern.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madanala.tern.model.TrajectoryForecast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun HourlyTrendChart(
    trajectory: TrajectoryForecast,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val windColor = MaterialTheme.colorScheme.primary
    val cloudColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
    
    // Data extraction
    val waypoints = trajectory.waypoints.sortedBy { it.estimatedArrival }
    if (waypoints.isEmpty()) return

    val startTime = waypoints.first().estimatedArrival
    val endTime = waypoints.last().estimatedArrival
    val timeRange = max(1L, endTime - startTime)

    // Find max values for scaling
    val maxWind = waypoints.maxOfOrNull { wp -> 
        wp.forecast.wind.maxOfOrNull { it.speed } ?: 0.0 
    } ?: 20.0
    val yMaxWind = max(20.0, maxWind * 1.2) // Minimum 20km/h scale, 20% buffer

    Box(modifier = modifier
        .fillMaxWidth()
        .height(200.dp)
        .padding(16.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val bottomPadding = 40.dp.toPx()
            val chartHeight = height - bottomPadding
            
            // Draw Grid Lines & Y-Axis Labels (Wind)
            val steps = 4
            val stepHeight = chartHeight / steps
            
            for (i in 0..steps) {
                val y = stepHeight * i
                val value = yMaxWind - (yMaxWind / steps * i)
                
                // Grid line
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )
                
                // Label
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        "${value.roundToInt()} km/h",
                        10f,
                        y - 10f,
                        android.graphics.Paint().apply {
                            color = labelColor.toArgb()
                            textSize = 10.sp.toPx()
                        }
                    )
                }
            }

            // Draw Cloud Cover (Bars)
            waypoints.forEach { wp ->
                val timeOffset = (wp.estimatedArrival - startTime).toFloat() / timeRange * width
                val cloudCover = wp.forecast.cloudCover.firstOrNull()?.percentage ?: 0.0
                
                val barHeight = (cloudCover / 100.0 * chartHeight).toFloat()
                
                drawRect(
                    color = cloudColor,
                    topLeft = Offset(timeOffset - 10f, chartHeight - barHeight),
                    size = Size(20f, barHeight)
                )
            }

            // Draw Wind Speed (Line)
            val windPath = Path()
            waypoints.forEachIndexed { index, wp ->
                val timeOffset = (wp.estimatedArrival - startTime).toFloat() / timeRange * width
                val windSpeed = wp.forecast.wind.firstOrNull()?.speed ?: 0.0
                val y = chartHeight - (windSpeed / yMaxWind * chartHeight).toFloat()
                
                if (index == 0) {
                    windPath.moveTo(timeOffset, y)
                } else {
                    windPath.lineTo(timeOffset, y)
                }
                
                // Draw point
                drawCircle(
                    color = windColor,
                    radius = 4.dp.toPx(),
                    center = Offset(timeOffset, y)
                )
            }
            
            drawPath(
                path = windPath,
                color = windColor,
                style = Stroke(width = 3.dp.toPx())
            )
            
            // Draw X-Axis Labels (Time)
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                
            waypoints.forEachIndexed { index, wp ->
                // Show label for first, last, and some middle points to avoid crowding
                if (index == 0 || index == waypoints.size - 1 || (index % 3 == 0)) {
                    val timeOffset = (wp.estimatedArrival - startTime).toFloat() / timeRange * width
                    val timeStr = timeFormatter.format(Instant.ofEpochMilli(wp.estimatedArrival))
                    
                    drawContext.canvas.nativeCanvas.apply {
                        drawText(
                            timeStr,
                            timeOffset - 30f, // Center align rough adjustment
                            height - 10f,
                            android.graphics.Paint().apply {
                                color = labelColor.toArgb()
                                textSize = 12.sp.toPx()
                            }
                        )
                    }
                }
            }
        }
        
        // Legend or Title
        Text(
            text = "Wind & Cloud Trend",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
