package com.ternparagliding.overlay.mezulla

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.ternparagliding.mezulla.redux.KnownPeer
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position
import org.osmdroid.util.GeoPoint
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Screen-edge indicators for buddies that are off the current map view.
 *
 * On a wide cross-country flight (e.g. Bir Billing) peers are routinely
 * tens of km away — far outside the follow-cam's look-ahead frame — so
 * their on-map [PeerLayer] HUD is not visible. This overlay keeps every
 * fresh buddy accounted for: for each peer that projects outside the
 * viewport, it pins a chip to the screen edge pointing toward them, with
 * callsign and distance. Peers that are on-screen (the HUD shows them) and
 * lost peers (no reliable bearing) get no chip.
 *
 * This is a Compose overlay, not a map layer, because the chips live in
 * screen space (pinned to edges), and — unlike GPU-drawn symbols — they're
 * assertable via Compose semantics in instrumented tests.
 */
@Composable
fun OffScreenPeerIndicators(
    peers: Map<Long, KnownPeer>,
    ownLocation: GeoPoint?,
    cameraState: CameraState,
    now: Instant = Instant.now(),
) {
    // Read camera position + projection so the overlay recomposes as the map
    // moves. Both can be unset before the map is ready — bail until then.
    val cameraReady = runCatching { cameraState.position }.getOrNull()
    val projection = runCatching { cameraState.projection }.getOrNull()
    if (cameraReady == null || projection == null) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val cx = wPx / 2f
        val cy = hPx / 2f
        // Keep chips clear of the screen edges and the right-side control
        // dock (~88dp wide) so they're fully visible and don't sit under it.
        val edge = with(density) { 14.dp.toPx() }
        val dockInset = with(density) { 92.dp.toPx() }
        val xMin = edge
        val xMax = wPx - dockInset
        val yMin = edge
        val yMax = hPx - edge

        for ((_, peer) in peers) {
            val fix = peer.lastPosition ?: continue
            val staleness = MezullaPeerTextFormatter.computeStaleness(peer, now)
            if (staleness == MezullaPeerTextFormatter.StalenessLevel.LOST) continue

            val dp = projection.screenLocationFromPosition(
                Position(longitude = fix.longitudeDeg, latitude = fix.latitudeDeg)
            )
            val sx = with(density) { dp.x.toPx() }
            val sy = with(density) { dp.y.toPx() }
            if (sx in 0f..wPx && sy in 0f..hPx) continue // on-screen → HUD shows it

            // Clamp the centre→peer ray onto the inset viewport rectangle.
            var dx = sx - cx
            var dy = sy - cy
            if (dx == 0f && dy == 0f) continue
            var t = Float.MAX_VALUE
            if (dx > 0) t = minOf(t, (xMax - cx) / dx) else if (dx < 0) t = minOf(t, (xMin - cx) / dx)
            if (dy > 0) t = minOf(t, (yMax - cy) / dy) else if (dy < 0) t = minOf(t, (yMin - cy) / dy)
            if (t <= 0f || t == Float.MAX_VALUE) continue
            val ex = cx + dx * t
            val ey = cy + dy * t
            val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

            val callsign = MezullaPeerTextFormatter.callsign(peer).uppercase()
            val color = puckColorFor(staleness)
            val distText = ownLocation?.let {
                formatDistance(it.distanceToAsDouble(GeoPoint(fix.latitudeDeg, fix.longitudeDeg)))
            } ?: ""

            // Position the chip centred on the clamped edge point.
            val chipApproxHalfPx = with(density) { 60.dp.toPx() }
            val px = (ex - chipApproxHalfPx).roundToInt()
            val py = (ey - with(density) { 12.dp.toPx() }).roundToInt()

            Row(
                modifier = Modifier
                    .offset { IntOffset(px.coerceIn(0, (wPx - 1).toInt()), py.coerceIn(0, (hPx - 1).toInt())) }
                    .background(Color(0xCC141414), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("offscreen-peer-$callsign")
                    .semantics { contentDescription = "offscreen-peer:$callsign:$distText" },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Canvas(Modifier.size(14.dp)) {
                    rotate(angleDeg, pivot = center) {
                        val p = Path().apply {
                            moveTo(size.width, size.height / 2f)            // tip (points +x)
                            lineTo(size.width * 0.25f, size.height * 0.18f)
                            lineTo(size.width * 0.25f, size.height * 0.82f)
                            close()
                        }
                        drawPath(p, color)
                    }
                }
                Text(callsign, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (distText.isNotEmpty()) {
                    Text(distText, color = Color(0xFFB0B0B0), fontSize = 11.sp)
                }
            }
        }
    }
}

private fun puckColorFor(s: MezullaPeerTextFormatter.StalenessLevel): Color = when (s) {
    MezullaPeerTextFormatter.StalenessLevel.FRESH -> Color(0xFF4CAF50)
    MezullaPeerTextFormatter.StalenessLevel.AGING -> Color(0xFFFFD600)
    MezullaPeerTextFormatter.StalenessLevel.STALE -> Color(0xFFFF9100)
    MezullaPeerTextFormatter.StalenessLevel.LOST -> Color(0xFF9E9E9E)
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()}m" else String.format("%.1fkm", meters / 1000.0)
