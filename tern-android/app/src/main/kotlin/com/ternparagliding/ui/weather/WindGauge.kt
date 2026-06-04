package com.ternparagliding.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            val arrowColor = Color.White
            val dialColor = Color(0xFF121212) // Deep Charcoal
            val glassColor = Color.White.copy(alpha = 0.05f)
            
            Canvas(modifier = Modifier.size(size)) {
                val strokeWidth = size.toPx() * 0.1f
                val radius = (size.toPx() - strokeWidth) / 2
                val center = Offset(size.toPx() / 2, size.toPx() / 2)
                
                // 1. Dial Background with Radial Gradient for Depth
                val dialGradient = Brush.radialGradient(
                    colors = listOf(Color(0xFF2A2A2A), Color(0xFF121212)),
                    center = center,
                    radius = radius + strokeWidth
                )
                drawCircle(
                    brush = dialGradient,
                    radius = radius + strokeWidth,
                    center = center
                )

                // 2. Background Track (Semi-transparent etched look)
                drawArc(
                    color = Color.White.copy(alpha = 0.05f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )

                // 3. Precision Ticks (Aviation style)
                for (angle in 0..270 step 5) {
                    val isMajor = angle % 45 == 0
                    val isMedium = angle % 15 == 0 && !isMajor
                    
                    val tickLen = when {
                        isMajor -> strokeWidth * 1.0f
                        isMedium -> strokeWidth * 0.6f
                        else -> strokeWidth * 0.3f
                    }
                    
                    val tickAlpha = when {
                        isMajor -> 0.9f
                        isMedium -> 0.6f
                        else -> 0.3f
                    }
                    
                    val tickAngle = Math.toRadians((angle + 135).toDouble())
                    val startRadius = radius - strokeWidth/2
                    val endRadius = startRadius + tickLen
                    
                    val innerPoint = Offset(
                        (center.x + startRadius * Math.cos(tickAngle)).toFloat(),
                        (center.y + startRadius * Math.sin(tickAngle)).toFloat()
                    )
                    val outerPoint = Offset(
                        (center.x + endRadius * Math.cos(tickAngle)).toFloat(),
                        (center.y + endRadius * Math.sin(tickAngle)).toFloat()
                    )
                    
                    drawLine(
                        color = Color.White.copy(alpha = tickAlpha),
                        start = innerPoint,
                        end = outerPoint,
                        strokeWidth = (if (isMajor) 1.5.dp else 1.dp).toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // 4. Dynamic Speed Arc with Color-Coded Zones
                val sweepAngle = 270f * (value / maxValue).coerceIn(0.0, 1.0).toFloat()
                val arcColor = when {
                    value < 15.0 -> Color(0xFF22C55E) // Green (Safe)
                    value < 25.0 -> Color(0xFFEAB308) // Yellow (Caution)
                    else -> Color(0xFFEF4444) // Red (Danger)
                }
                
                // Active Speed Arc
                drawArc(
                    color = arcColor.copy(alpha = 0.8f),
                    startAngle = 135f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth * 1.2f, cap = StrokeCap.Butt)
                )

                // 5. Aviation Needle (Tapered with Pivot)
                if (directionDegrees != null) {
                    val needleLen = radius * 0.95f
                    rotate(degrees = directionDegrees.toFloat() - 90f, pivot = center) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            // Tapered needle
                            moveTo(center.x, center.y - needleLen)
                            lineTo(center.x - (radius * 0.08f), center.y + (radius * 0.15f))
                            lineTo(center.x + (radius * 0.08f), center.y + (radius * 0.15f))
                            close()
                        }
                        
                        // Needle Shadow
                        drawPath(path, Color.Black.copy(alpha = 0.5f), 
                            style = Stroke(width = 2.dp.toPx()))
                        
                        // Needle Body (Refinement: High-contrast white/silver)
                        drawPath(path, Color(0xFFF5F5F7))
                        
                        // Contrast spine
                        drawLine(
                            color = Color.Black.copy(alpha = 0.1f),
                            start = Offset(center.x, center.y - needleLen),
                            end = center,
                            strokeWidth = 1.dp.toPx()
                        )
                        
                        // Precision point (Aviation Red)
                        drawCircle(Color(0xFFE53935), radius = 2.5.dp.toPx(), 
                            center = Offset(center.x, center.y - needleLen))
                    }
                }
                
                // 6. Master Pivot Hub
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0xFF444444), Color.Black)),
                    radius = radius * 0.15f,
                    center = center
                )
                drawCircle(color = Color.White.copy(alpha = 0.9f), radius = 2.dp.toPx(), center = center)

                // 7. Premium Glass Reflection
                val glassBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = 0.15f),
                    0.5f to Color.Transparent
                )
                drawCircle(brush = glassBrush, radius = radius + strokeWidth, center = center)
                
                // Top crescent highlight
                drawArc(
                    color = Color.White.copy(alpha = 0.08f),
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 0.9f, center.y - radius * 0.9f),
                    size = Size(radius * 1.8f, radius * 1.8f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            
            // Value Text (Inset to center)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${value.roundToInt()}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f)
                    ),
                    color = Color.White,
                    modifier = Modifier.testTag("WindGaugeValue")
                )
                if (size >= 60.dp) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

