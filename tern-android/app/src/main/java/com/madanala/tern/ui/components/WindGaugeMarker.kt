package com.madanala.tern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.madanala.tern.R
import com.madanala.tern.ui.weather.WindGauge

@Composable
fun WindGaugeMarker(
    speed: Double,
    direction: Double,
    gust: Double = 0.0,
    isStale: Boolean = false,
    hasConvectiveDanger: Boolean = false,
    hasThunderstorm: Boolean = false,
    showHazards: Boolean = true
) {
    val backgroundColor = if (isStale) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val borderColor = if (isStale) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary
    val contentColor = if (isStale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    
    // Gradient colors for the indicator
    val activeGradient = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
    val staleGradient = listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)
    val indicatorGradient = if (isStale) staleGradient else activeGradient
 
    // Aviation-Grade Hazard Visuals (RFC 005) - Gated by showHazards
    val hazardBorderWidth = if (showHazards && (hasConvectiveDanger || hasThunderstorm)) 3.dp else 1.dp
    val hazardBorderColor = when {
        showHazards && hasThunderstorm -> Color(0xFFE53935) // Critical Red
        showHazards && hasConvectiveDanger -> Color(0xFFFFBF00) // Amber Warning
        isStale -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    }

    // [RFC 005] Proper "Halo" glow effect for critical hazards
    val haloModifier = if (showHazards && (hasConvectiveDanger || hasThunderstorm)) {
        Modifier.shadow(
            elevation = 8.dp,
            shape = CircleShape,
            ambientColor = hazardBorderColor,
            spotColor = hazardBorderColor
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(56.dp) // Standard marker size
            .then(haloModifier)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(hazardBorderWidth, hazardBorderColor, CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        WindGauge(
            value = speed,
            maxValue = 40.0, // Aviation scale
            label = "", // No label for marker to save space
            unit = "kt",
            directionDegrees = direction,
            gradientColors = indicatorGradient,
            size = 48.dp,
            modifier = Modifier.align(Alignment.Center)
        )

        // Lightning Bolt Overlay for Thunderstorms (Gated by showHazards)
        if (showHazards && hasThunderstorm) {
             Icon(
                painter = painterResource(id = R.drawable.storm_24),
                contentDescription = "Thunderstorm Warning",
                tint = Color(0xFFE53935),
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.TopEnd)
                    .background(Color.White.copy(alpha = 0.8f), CircleShape)
                    .padding(2.dp)
            )
        }
    }
}
