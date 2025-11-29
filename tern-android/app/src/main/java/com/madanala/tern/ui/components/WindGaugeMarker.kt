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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.madanala.tern.ui.weather.WindGauge

@Composable
fun WindGaugeMarker(
    speed: Double,
    direction: Double,
    gust: Double = 0.0,
    isStale: Boolean = false
) {
    val backgroundColor = if (isStale) Color.LightGray else MaterialTheme.colorScheme.surface
    val borderColor = if (isStale) Color.Gray else MaterialTheme.colorScheme.primary
    val contentColor = if (isStale) Color.Gray else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .size(56.dp) // Standard marker size
            .clip(CircleShape)
            .background(backgroundColor)
            .border(2.dp, borderColor, CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        WindGauge(
            value = speed,
            maxValue = 40.0, // Aviation scale
            label = "", // No label for marker to save space
            unit = "kt",
            directionDegrees = direction,
            gradientColors = if (isStale) listOf(Color.Gray, Color.Gray) else listOf(Color.Blue, Color.Cyan),
            size = 48.dp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
