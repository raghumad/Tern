package com.madanala.tern.overlay.mezulla

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * Animation states for Mezulla peer icons.
 * Only animate things that NEED pilot attention:
 * - SOS buzzes because it's life-safety
 * - Stale pulses because it's a growing concern
 * - Fresh is static — no distraction needed
 */
data class PeerIconAnimation(
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val scale: Float = 1f,
)

@Composable
fun rememberSosAnimation(): PeerIconAnimation {
    val transition = rememberInfiniteTransition(label = "sos_buzz")
    val rotation by transition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sos_rotation",
    )
    return PeerIconAnimation(rotation = rotation, alpha = 1f, scale = 1.1f)
}

@Composable
fun rememberStaleAnimation(): PeerIconAnimation {
    val transition = rememberInfiniteTransition(label = "stale_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stale_alpha",
    )
    return PeerIconAnimation(alpha = alpha)
}

@Composable
fun rememberAnimationForStaleness(
    staleness: MezullaPeerTextFormatter.StalenessLevel,
    isSos: Boolean = false,
): PeerIconAnimation {
    return when {
        isSos -> rememberSosAnimation()
        staleness == MezullaPeerTextFormatter.StalenessLevel.STALE -> rememberStaleAnimation()
        staleness == MezullaPeerTextFormatter.StalenessLevel.LOST -> PeerIconAnimation(alpha = 0.5f)
        staleness == MezullaPeerTextFormatter.StalenessLevel.AGING -> PeerIconAnimation(alpha = 0.8f)
        else -> PeerIconAnimation() // FRESH — static, full opacity
    }
}
