package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.mezulla.connection.LinkState
import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.overlay.mezulla.MezullaPeerTextFormatter
import com.ternparagliding.overlay.mezulla.MezullaPeerTextFormatter.StalenessLevel
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.redux.MapStore
import java.time.Instant

/**
 * The **team sheet** — the single home for everything peer-related, opened by tapping the buddies
 * chip. It replaces the old standalone view-mode dock button: the SAFETY / CLIMB / TACTICAL choice
 * now lives here next to the roster it actually affects, so the prime dock stays for the controls
 * the pilot reaches for constantly (settings, recenter, task).
 *
 * Two things, no more: pick the metric the team is read by, and see every buddy — callsign, the
 * mode-specific detail, and a staleness dot — sorted nearest-first so the closest traffic is on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MezullaTeamSheet(
    store: MapStore,
    onDismiss: () -> Unit,
) {
    val state by store.state.collectAsState()
    val peerState = state.peerState
    // A ticking wall clock so "Xs ago" actually advances between updates and resets
    // when a fresh position arrives. peerState.lastEventTime CANNOT drive this: the
    // freshest event is the active buddy's own position, so its lastSeenAt always
    // equals lastEventTime → the age cancels to a permanent "0s ago" (looks frozen/
    // hardcoded). Wall time also lets a buddy that goes quiet visibly age out.
    val now by produceState(initialValue = Instant.now()) {
        while (true) {
            value = Instant.now()
            kotlinx.coroutines.delay(1000)
        }
    }
    val pilotPos = state.userLocation?.let { Position(it.latitude, it.longitude) }

    // Nearest-first when we know where we are; otherwise freshest-first. Distance is what a pilot
    // scanning for traffic cares about; absent a fix, recency is the next-best ordering.
    val peers = peerState.peers.values.sortedWith(
        if (pilotPos != null) compareBy { p ->
            p.lastPosition?.let { pilotPos.distanceKm(Position(it.latitudeDeg, it.longitudeDeg)) }
                ?: Double.MAX_VALUE
        } else compareByDescending { it.lastSeenAt }
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // Header: title + live link state.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Team", style = MaterialTheme.typography.titleLarge)
                LinkStatusText(peerState.linkState, peers.size)
            }

            Spacer(Modifier.height(16.dp))

            if (peers.isEmpty()) {
                Text(
                    "No buddies in range yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                )
            } else {
                peers.forEach { peer ->
                    PeerRosterRow(peer, pilotPos, now)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun LinkStatusText(linkState: LinkState, peerCount: Int) {
    val (text, color) = when (linkState) {
        LinkState.UP -> "$peerCount in range" to Color(0xFF22C55E)
        LinkState.DOWN -> "board off" to MaterialTheme.colorScheme.onSurfaceVariant
        LinkState.NEVER_PAIRED -> "not paired" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color, fontSize = 13.sp)
}


@Composable
private fun PeerRosterRow(
    peer: KnownPeer,
    pilotPos: Position?,
    now: Instant,
) {
    val staleness = MezullaPeerTextFormatter.computeStaleness(peer, now)
    val dotColor = Color(android.graphics.Color.parseColor(MezullaPeerTextFormatter.colorHexForStaleness(staleness)))
    val callsign = MezullaPeerTextFormatter.callsign(peer)
    val detail = peer.lastPosition?.let { fix ->
        MezullaPeerTextFormatter.detailLine(peer, fix, staleness, pilotPos, now)
    } ?: "no position"

    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Staleness dot — same colour language as the on-map puck (fresh white → lost grey).
        Box(
            Modifier
                .size(10.dp)
                .background(dotColor, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                callsign,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = if (staleness == StalenessLevel.LOST)
                    MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
