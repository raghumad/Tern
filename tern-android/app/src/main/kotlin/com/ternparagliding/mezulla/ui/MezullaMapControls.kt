package com.ternparagliding.mezulla.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.redux.ActiveAlert
import com.ternparagliding.mezulla.redux.PeerState
import com.ternparagliding.redux.MezullaViewMode
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// 64dp minimum for glove-friendly touch targets
private val GLOVE_FRIENDLY_SIZE = 64.dp

/**
 * View-mode toggle button for the map edge.
 *
 * Shows the active Mezulla view mode (SAFETY / CLIMB / TACTICAL).
 * Single tap cycles to the next mode. 64dp for glove-friendliness.
 *
 * Only visible when the link is UP -- no point showing mode controls
 * when there are no peers to display.
 */
@Composable
fun MezullaViewModeButton(
    viewMode: MezullaViewMode,
    linkState: LinkState,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (linkState != LinkState.UP) return

    val (icon, label) = when (viewMode) {
        com.ternparagliding.redux.MezullaViewMode.SAFETY -> "👁" to "SAFETY"   // 👁 eye = watching peers
        com.ternparagliding.redux.MezullaViewMode.CLIMB -> "🌀" to "CLIMB"    // 🌀 spiral = thermalling
        com.ternparagliding.redux.MezullaViewMode.TACTICAL -> "🧭" to "TACT"   // 🧭 compass = bearing
    }
    Button(
        onClick = onCycle,
        modifier = modifier
            .size(GLOVE_FRIENDLY_SIZE)
            .testTag("mezulla_view_mode_button"),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1A1A2E).copy(alpha = 0.85f),
            contentColor = Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = icon, fontSize = 20.sp, maxLines = 1)
            Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * SOS alert banner. Slides in from the top when there are undismissed
 * active alerts. Shows the first undismissed alert; additional alerts
 * become visible as the pilot dismisses earlier ones.
 *
 * Haptic feedback on first appearance so the pilot notices even if
 * they're looking at the sky.
 */
@Composable
fun SosAlertBanner(
    peerState: PeerState,
    dismissedSosAlerts: Set<Long>,
    userLocation: GeoPoint?,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val undismissed = peerState.activeAlerts.filter { alert ->
        alert.acknowledgedAt == null &&
            alert.senderIdentity.nodeNumber !in dismissedSosAlerts
    }
    val alertToShow = undismissed.firstOrNull()

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(alertToShow?.senderIdentity?.nodeNumber) {
        if (alertToShow != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    AnimatedVisibility(
        visible = alertToShow != null,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        alertToShow?.let { alert ->
            SosBannerContent(
                alert = alert,
                userLocation = userLocation,
                onDismiss = { onDismiss(alert.senderIdentity.nodeNumber) },
            )
        }
    }
}

@Composable
private fun SosBannerContent(
    alert: ActiveAlert,
    userLocation: GeoPoint?,
    onDismiss: () -> Unit,
) {
    val callsign = alert.senderIdentity.longName
        ?: alert.senderIdentity.shortName
        ?: alert.senderIdentity.hexId

    val locationText = if (userLocation != null && alert.lastKnownPosition != null) {
        val fix = alert.lastKnownPosition
        val peerPoint = GeoPoint(fix.latitudeDeg, fix.longitudeDeg)
        val distanceKm = userLocation.distanceToAsDouble(peerPoint) / 1000.0
        val bearing = computeBearing(userLocation, peerPoint)
        val cardinal = degreesToCardinal(bearing)
        "${String.format("%.1f", distanceKm)} km $cardinal"
    } else {
        "position unknown"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFCC0000))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("sos_alert_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "SOS from $callsign — $locationText",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(GLOVE_FRIENDLY_SIZE)
                .testTag("sos_dismiss_button"),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss SOS alert",
                tint = Color.White,
            )
        }
    }
}

/**
 * Mezulla status indicator bar. Shows connection state and peer count.
 *
 * Graceful degradation:
 * - NEVER_PAIRED: nothing shown (invisible).
 * - DOWN: "Mezulla off" in subdued text.
 * - UP: "{N} peers . Mezulla connected" in normal text.
 */
@Composable
fun MezullaStatusIndicator(
    peerState: PeerState,
    modifier: Modifier = Modifier,
) {
    when (peerState.linkState) {
        LinkState.NEVER_PAIRED -> {
            // Invisible. No board has ever been paired.
        }

        LinkState.DOWN -> {
            Row(
                modifier = modifier
                    .background(Color(0xFF1A1A2E).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("mezulla_status_indicator"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Mezulla",
                    color = Color.Gray,
                    fontSize = 12.sp,
                )
                Text(
                    text = "●",
                    color = Color.Gray,
                    fontSize = 8.sp,
                )
                Text(
                    text = "off",
                    color = Color.Gray,
                    fontSize = 12.sp,
                )
            }
        }

        LinkState.UP -> {
            val peerCount = peerState.peers.size
            Row(
                modifier = modifier
                    .background(Color(0xFF1A1A2E).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("mezulla_status_indicator"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "$peerCount peers",
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Text(
                    text = "·",
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Text(
                    text = "Mezulla",
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Text(
                    text = "●",
                    color = Color(0xFF4CAF50),
                    fontSize = 8.sp,
                )
                Text(
                    text = "connected",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Compact, glance-only Mezulla status badge intended for the top-right
 * corner of the map (next to the compass). Shows the brand mark (M
 * with two radio-wave arcs), a small peer count, and a colored ring
 * that signals link state at a glance.
 *
 * Per one-handed-UI principle: visual-only info goes in the hard-to-
 * reach corner; the pilot just glances at it, never taps it.
 *
 *   NEVER_PAIRED → invisible
 *   DOWN         → grey M, no count, dim ring
 *   UP           → white M, peer count, green ring
 */
@Composable
fun MezullaStatusBadge(
    peerState: PeerState,
    modifier: Modifier = Modifier,
) {
    if (peerState.linkState == LinkState.NEVER_PAIRED) return

    val isUp = peerState.linkState == LinkState.UP
    val ringColor = if (isUp) Color(0xFF4CAF50) else Color.Gray
    val iconColor = if (isUp) Color.White else Color(0xFFAAAAAA)
    val peerCount = if (isUp) peerState.peers.size else null

    Box(
        modifier = modifier
            .size(40.dp)
            .background(Color(0xFF1A1A2E).copy(alpha = 0.7f), CircleShape)
            .testTag("mezulla_status_badge"),
        contentAlignment = Alignment.Center,
    ) {
        MezullaBrandIcon(size = 22.dp, color = iconColor)

        // Status dot in the bottom-right corner (like a notification badge)
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.BottomEnd)
                .background(ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (peerCount != null && peerCount > 0) {
                Text(
                    text = peerCount.toString(),
                    color = Color.White,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// -- Geometry helpers (duplicated from MezullaOverlayManager to avoid
// pulling in the full overlay dependency for Compose-only code) --------

private fun computeBearing(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val x = sin(dLon) * cos(lat2)
    val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val bearing = Math.toDegrees(atan2(x, y))
    return (bearing + 360) % 360
}

private fun degreesToCardinal(degrees: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = ((degrees + 22.5) / 45.0).toInt() % 8
    return dirs[index]
}
