package com.madanala.tern.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madanala.tern.R
import com.madanala.tern.ui.overlays.RankingTier
import com.madanala.tern.model.LocationSource
import com.madanala.tern.model.LocationType
import com.madanala.tern.model.UnifiedLocation
import com.madanala.tern.utils.WeatherForecast

/**
 * [RFC 005] Unified Location Marker
 * High-fidelity, aviation-grade marker for both PG Spots and Waypoints.
 * Implements 3-stage adaptive scaling, pulsing hazard visuals, and 64dp touch targets.
 */
@Composable
fun LocationMarker(
    location: UnifiedLocation,
    zoom: Double,
    forecast: WeatherForecast? = null,
    isSelected: Boolean = false,
    rankingTier: RankingTier = RankingTier.PATH, // [RSE] Replaces binary isPriority
    onClick: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val hasConvectiveDanger = forecast?.hasConvectiveDanger() == true
    val hasThunderstorm = forecast?.hasThunderstorm() == true
    
    // --- 🎯 RFC 005 Adaptive Scaling Logic (Refined for Info-First & Safety) ---
    val showHazard = hasConvectiveDanger || hasThunderstorm
    val (scale, showDetails, alpha) = when {
        rankingTier == RankingTier.TARGET || isSelected || showHazard -> Triple(1.0f, true, 1.0f) 
        rankingTier == RankingTier.CONTEXT -> Triple(0.2f, false, 0.5f) // [RSE] Forces Pin-prick mode
        zoom >= 14.0 -> Triple(1.0f, true, 1.0f)                           
        zoom >= 10.0 -> Triple(0.6f, false, 0.9f)                          
        else -> Triple(0.2f, false, 0.5f)                                 
    }

    // --- ⚡ Micro-Animations (RFC 005) ---
    val infiniteTransition = rememberInfiniteTransition(label = "HazardAnimations")
    
    // Pulsing Halo for Convective Danger (0.8s period)
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HaloPulse"
    )
    
    // Flashing Lightning for Thunderstorms (1Hz)
    val lightningAlpha by infiniteTransition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.0f at 0
                1.0f at 100
                1.0f at 500
                0.0f at 600
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "LightningFlash"
    )

    Box(
        modifier = Modifier
            .size(64.dp) // Mandatory 64dp touch target (Aviation DoD)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
            .alpha(alpha)
            .testTag("LocationMarker_${location.name}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(scale)
        ) {
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                // 1. Hazard Halo (Pulsing Amber/Red)
                if ((hasConvectiveDanger || hasThunderstorm) && showDetails) {
                    val haloColor = if (hasThunderstorm) Color(0xFFE53935) else Color(0xFFFFBF00)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .scale(haloScale)
                            .shadow(
                                elevation = 8.dp,
                                shape = CircleShape,
                                ambientColor = haloColor,
                                spotColor = haloColor
                            )
                            .border(2.dp, haloColor, CircleShape)
                            .background(Color.Transparent)
                            .testTag("HazardHalo_${location.name}")
                    )
                }

                // 2. Selection Border
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .border(3.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
                    )
                }

                // 3. Base Marker Container
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else (if (rankingTier == RankingTier.CONTEXT) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant),
                    tonalElevation = if (rankingTier == RankingTier.CONTEXT) 0.dp else 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // [RSE] In Context (Pin-prick) mode, we only show a small colored dot
                        if (rankingTier == RankingTier.CONTEXT) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        CircleShape
                                    )
                            )
                        } else {
                            // Icon selection based on Source + Type
                            val iconRes = when (location.source) {
                                LocationSource.PG_SPOT -> R.mipmap.ic_launcher
                                LocationSource.WAYPOINT -> when (location.type) {
                                    LocationType.LAUNCH -> R.drawable.ic_waypoint_launch
                                    LocationType.LANDING -> R.drawable.ic_waypoint_landing
                                    LocationType.SSS -> R.drawable.ic_waypoint_sss
                                    LocationType.ESS -> R.drawable.ic_waypoint_ess
                                    LocationType.GOAL -> R.drawable.ic_waypoint_goal
                                    else -> R.drawable.ic_waypoint_turnpoint
                                }
                            }

                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // 4. Integrated Wind Gauge (Internal)
                        if (showDetails && rankingTier != RankingTier.CONTEXT) {
                            forecast?.current?.let { current ->
                                WindGaugeMarker(
                                    speed = current.wind.speed,
                                    direction = current.wind.direction,
                                    isStale = forecast.isStale(),
                                    showHazards = false // We handle hazards externally
                                )
                            }
                        }
                    }
                }

                // 5. Lightning Bolt Overlay (Flashing)
                if (hasThunderstorm && showDetails) {
                    Icon(
                        painter = painterResource(id = R.drawable.storm_24),
                        contentDescription = "Thunderstorm",
                        tint = Color(0xFFE53935),
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.TopEnd)
                            .alpha(lightningAlpha)
                            .offset(x = 4.dp, y = (-4).dp)
                            .background(Color.White, CircleShape)
                            .padding(2.dp)
                            .testTag("HazardBolt_${location.name}")
                    )
                }
            }

            // 6. Adaptive LOD Label
            if (showDetails) {
                location.computeLabel()?.let { label ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = label.uppercase(),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
