package com.madanala.tern.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint

/**
 * Universal animated map overlay composable that provides smooth fade-in/fade-out transitions
 * for any type of map overlay (airspaces, terrain, PG spots, weather, etc.).
 *
 * This replaces the need for individual animation implementations across different overlay types.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedMapOverlay(
    overlayId: String,
    shouldShow: Boolean,
    animationDuration: Int = 500,
    modifier: Modifier = Modifier,
    onAnimationComplete: (String) -> Unit = {},
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = shouldShow,
        enter = fadeIn(animationSpec = tween(durationMillis = animationDuration)) +
                scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(animationSpec = tween(durationMillis = animationDuration)) +
               scaleOut(targetScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessLow)),
        modifier = modifier
    ) {
        content()
    }

    // Handle animation lifecycle callbacks
    LaunchedEffect(shouldShow, overlayId) {
        if (!shouldShow) {
            delay(animationDuration.toLong() + 100) // Small buffer
            onAnimationComplete(overlayId)
        }
    }
}

/**
 * Calculate transition zone based on distance from map center
 */
fun calculateTransitionZone(
    overlayLocation: GeoPoint,
    mapCenter: GeoPoint,
    overlayPriority: Int = 5
): String {
    val distance = mapCenter.distanceToAsDouble(overlayLocation) / 1000.0 // Convert to km

    // Safety-critical overlays always get CORE zone regardless of distance
    if (overlayPriority <= 2) {
        return "CORE"
    }

    return when {
        distance <= 50.0 -> "CORE"
        distance <= 100.0 -> "NEAR"
        distance <= 200.0 -> "MID"
        distance <= 400.0 -> "FAR"
        else -> "EXTREME"
    }
}