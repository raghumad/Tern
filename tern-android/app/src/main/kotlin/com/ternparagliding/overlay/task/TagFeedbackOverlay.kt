package com.ternparagliding.overlay.task

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.redux.MapStore
import com.ternparagliding.ui.theme.TernFontFamily

/**
 * Confirms a cylinder tag the pilot can't be staring at the screen to catch:
 * a **haptic buzz** + a **brief cyan flash** with the tagged waypoint's name.
 *
 * Watches `taggedWaypointIds`. A *growth* (new tag) fires feedback; a *shrink*
 * (task switch / [com.ternparagliding.redux.MapAction.ResetTaskProgress]) is
 * silent — that's bookkeeping, not an achievement.
 *
 * A screen-space Compose overlay (drawn last → on top), assertable via semantics.
 */
@Composable
fun TagFeedbackOverlay(store: MapStore) {
    val state by store.state.collectAsState()
    val context = LocalContext.current

    var lastTagged by remember { mutableStateOf(state.taggedWaypointIds) }
    var flashName by remember { mutableStateOf<String?>(null) }
    val flashAlpha = remember { Animatable(0f) }

    LaunchedEffect(state.taggedWaypointIds) {
        val current = state.taggedWaypointIds
        val added = current - lastTagged
        val removed = lastTagged - current
        lastTagged = current
        // Only a genuine new tag (grew, didn't also shrink — i.e. not a reset).
        if (added.isNotEmpty() && removed.isEmpty()) {
            val wpId = added.first()
            flashName = state.tasks.firstOrNull { it.id == state.selectedTaskId }
                ?.waypoints?.firstOrNull { it.id == wpId }?.displayName
            vibrateTag(context)
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(durationMillis = 750))
            flashName = null
        }
    }

    if (flashAlpha.value > 0f) {
        Box(Modifier.fillMaxSize()) {
            // A brief cyan scrim that fades out — peripheral-visible without obscuring for long.
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(flashAlpha.value * 0.35f)
                    .background(Color(0xFF1FE3DE))
            )
            val label = flashName
            if (!label.isNullOrBlank()) {
                Text(
                    "✓ ${label.uppercase()}",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontFamily = TernFontFamily.gruppo,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(flashAlpha.value)
                        .background(Color(0xCC0A1417), RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .testTag("tag-feedback")
                        .semantics { contentDescription = "tagged:$label" },
                )
            }
        }
    }
}

/** A short, firm buzz — felt through gloves, distinct from a vario beep. */
private fun vibrateTag(context: Context) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator?.hasVibrator() != true) return
    runCatching {
        vibrator.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
