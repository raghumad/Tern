package com.madanala.tern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madanala.tern.ui.theme.AeroSlate
import com.madanala.tern.ui.theme.AeroNeonCyan

/**
 * Route Leg Decoration Composable (RFC 005)
 * Provides high-contrast labels for route segments (distance and optional ETA).
 */
@Composable
fun LegDecoration(
    distanceKm: Double,
    etaMin: Int? = null,
    scale: Float = 1.0f,
    isVisible: Boolean = true // [RSE] Allow external suppression
) {
    if (!isVisible) return

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape((16.dp * scale)))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = (10.dp * scale), vertical = (4.dp * scale)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((8.dp * scale))
    ) {
        // Distance label (Primary metric)
        Text(
            text = String.format("%.1f km", distanceKm),
            color = MaterialTheme.colorScheme.primary,
            fontSize = (14.sp.value * scale).sp,
            fontWeight = FontWeight.Bold
        )

        // ETA label (Secondary metric) - Hide if scale is too small for readability
        if (etaMin != null && scale >= 0.5f) {
            Box(
                modifier = Modifier
                    .size(width = (1.dp * scale), height = (12.dp * scale))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            )
            
            Text(
                text = String.format("+%d min", etaMin),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontSize = (12.sp.value * scale).sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
