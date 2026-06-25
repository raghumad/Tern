package com.ternparagliding.mezulla.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.redux.ActiveAlert
import com.ternparagliding.mezulla.redux.PeerState
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// 64dp minimum for glove-friendly touch targets
private val GLOVE_FRIENDLY_SIZE = 64.dp

// Shared dock chrome so the buddies chip reads as part of the same family as the map controls.
private val CHIP_BG = Color(0xFF1A1A2E).copy(alpha = 0.55f)
private val LINK_GREEN = Color(0xFF22C55E)

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
 * The **buddies chip** — the one peer affordance on the map: a glance-readable people-icon +
 * live peer count, and the single tap into the [com.ternparagliding.ui.components.MezullaTeamSheet]
 * (roster + the SAFETY/CLIMB/TACTICAL read mode). Replaces the old pulsing Nerd-Font glyph badge
 * *and* the separate view-mode dock button — one element, in the dock's monochrome language.
 *
 *   NEVER_PAIRED → invisible (no board has ever been paired)
 *   DOWN         → grey group-off icon, no count (board powered off / out of range)
 *   UP           → green group icon + peer count
 *
 * [onClick] opens the team sheet; null leaves the chip non-interactive (e.g. previews/tests).
 */
@Composable
fun MezullaStatusBadge(
    peerState: PeerState,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (peerState.linkState == LinkState.NEVER_PAIRED) return

    val isUp = peerState.linkState == LinkState.UP
    val count = peerState.peers.size

    // Preserved test/observability hook (T7 BLE-reliability semantic match): the chip's content
    // description still encodes the exact link state + peer count, no visual scraping needed.
    val statusDescription = when (peerState.linkState) {
        LinkState.UP -> "mezulla-status:UP:peers=${peerState.peers.size}"
        LinkState.DOWN -> "mezulla-status:DOWN"
        LinkState.NEVER_PAIRED -> "mezulla-status:NEVER_PAIRED"
    }

    Row(
        modifier = modifier
            .height(32.dp)
            .shadow(3.dp, RoundedCornerShape(16.dp))
            .background(CHIP_BG, RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp)
            .testTag("mezulla_status_badge")
            .semantics { contentDescription = statusDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (isUp) Icons.Filled.Group else Icons.Filled.GroupOff,
            contentDescription = null,
            tint = if (isUp) LINK_GREEN else Color.Gray,
            modifier = Modifier.size(18.dp),
        )
        if (isUp) {
            Text(text = "$count", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
