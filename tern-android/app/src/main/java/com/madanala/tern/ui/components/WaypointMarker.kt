package com.madanala.tern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madanala.tern.R
import com.madanala.tern.model.Waypoint
import com.madanala.tern.ui.theme.AeroSlate
import com.madanala.tern.ui.theme.AeroNeonCyan
import com.madanala.tern.ui.theme.AeroOrange
import com.madanala.tern.utils.WeatherForecast

/**
 * Unified Waypoint Marker Composable (RFC 005)
 * Merges iconography, wind data, hazards, and labels into a high-contrast map overlay.
 */
@Composable
fun WaypointMarker(
    waypoint: Waypoint,
    forecast: WeatherForecast? = null,
    isSelected: Boolean = false,
    isDragging: Boolean = false,
    scale: Float = 1.0f,
    showDetails: Boolean = true
) {
    val hasConvectiveDanger = forecast?.hasConvectiveDanger() == true
    val hasThunderstorm = forecast?.hasThunderstorm() == true
    
    // Lift effect for dragging + Zoom scaling
    val baseScale = if (isDragging) 1.2f else 1.0f
    val finalScale = baseScale * scale
    val elevation = if (isDragging) 12.dp else (2.dp * scale)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding((8.dp * scale)) // Space for shadows/halos
    ) {
        Box(
            modifier = Modifier
                .size((56.dp * finalScale))
                .padding((4.dp * finalScale)), // Space for hazard border
            contentAlignment = Alignment.Center
        ) {
            // 🎯 RFC 005: Hazard Halo (Convective Danger) - Only show if details are enabled or scale is sufficient
            if ((hasConvectiveDanger || hasThunderstorm) && showDetails) {
                val haloColor = if (hasThunderstorm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(
                            elevation = (10.dp * scale),
                            shape = CircleShape,
                            ambientColor = haloColor,
                            spotColor = haloColor
                        )
                        .background(Color.Transparent)
                )
            }

            // Selection Highlight
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border((3.dp * scale), MaterialTheme.colorScheme.tertiary, CircleShape)
                )
            }

            // Base Marker Container
            Box(
                modifier = Modifier
                    .size((48.dp * finalScale))
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                    .border((1.dp * scale), MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Base Icon
                val iconRes = when (waypoint.type) {
                    Waypoint.Type.LAUNCH -> R.drawable.ic_waypoint_launch
                    Waypoint.Type.LANDING -> R.drawable.ic_waypoint_landing
                    Waypoint.Type.SSS -> R.drawable.ic_waypoint_sss
                    Waypoint.Type.ESS -> R.drawable.ic_waypoint_ess
                    Waypoint.Type.GOAL -> R.drawable.ic_waypoint_goal
                    else -> R.drawable.ic_waypoint_turnpoint
                }

                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size((32.dp * finalScale))
                )

                // Integrated Wind Gauge (Primary Layer) - Hide if scale is too small for readability
                if (scale >= 0.4f && showDetails) {
                    forecast?.current?.let { current ->
                        WindGaugeMarker(
                            speed = current.wind.speed,
                            direction = current.wind.direction,
                            gust = current.wind.gust,
                            isStale = forecast.isStale(),
                            hasConvectiveDanger = hasConvectiveDanger,
                            hasThunderstorm = hasThunderstorm,
                            showHazards = false
                        )
                    }
                }
            }

            // 🎯 RFC 005: Lightning Bolt Overlay
            if (hasThunderstorm && showDetails && scale >= 0.5f) {
                Icon(
                    painter = painterResource(id = R.drawable.storm_24),
                    contentDescription = "Thunderstorm",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size((20.dp * scale))
                        .align(Alignment.TopEnd)
                        .offset(x = (4.dp * scale), y = ((-4).dp * scale))
                        .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                        .padding((2.dp * scale))
                )
            }
        }

        // 🎯 High-Contrast Label with Backdrop (Adaptive LOD)
        if (showDetails && scale >= 0.3f) {
            waypoint.label?.let { label ->
                Spacer(modifier = Modifier.height((4.dp * scale)))
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape((4.dp * scale)),
                    modifier = Modifier.padding(horizontal = (4.dp * scale))
                ) {
                    Text(
                        text = label.uppercase(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = (12.sp.value * scale).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = (6.dp * scale), vertical = (2.dp * scale))
                    )
                }
            }
        }
    }
}

// Internal Surface for Label Backdrop
@Composable
private fun Surface(
    color: Color,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(color = color, shape = shape)
            .clip(shape)
    ) {
        content()
    }
}
