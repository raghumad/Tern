package com.ternparagliding.overlay.route

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.ui.theme.TernFontFamily
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Screen-edge indicator for the **active task waypoint** — the buddy-style
 * "next waypoint shows up on screen" guidance the pilot asked for. Sibling of
 * [com.ternparagliding.overlay.mezulla.OffScreenPeerIndicators]: when the active
 * waypoint projects outside the viewport (normal on a cross-country leg), pin a
 * chip to the screen edge pointing toward it, with its name/description, the
 * distance, and — when altitude is known — the glide ratio required to reach it.
 *
 * On-screen the waypoint already has its on-map marker (with the active
 * highlight), so no chip is drawn then, exactly like peers.
 *
 * A Compose overlay (screen space), not a map layer, so it can pin to edges and
 * is assertable via semantics in instrumented tests.
 */
@Composable
fun OffScreenWaypointIndicator(
    target: GeoPoint?,
    label: String,
    roleColor: Color,
    ownLocation: GeoPoint?,
    ownAltitudeM: Double?,
    targetAltM: Double?,
    cameraState: CameraState,
) {
    if (target == null) return

    val cameraReady = runCatching { cameraState.position }.getOrNull()
    val projection = runCatching { cameraState.projection }.getOrNull()
    if (cameraReady == null || projection == null) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val cx = wPx / 2f
        val cy = hPx / 2f
        // Keep clear of the edges and the right-side control dock (~88dp).
        val edge = with(density) { 14.dp.toPx() }
        val dockInset = with(density) { 92.dp.toPx() }
        val xMin = edge
        val xMax = wPx - dockInset
        val yMin = edge
        val yMax = hPx - edge

        val dp = projection.screenLocationFromPosition(
            Position(longitude = target.longitude, latitude = target.latitude)
        )
        val sx = with(density) { dp.x.toPx() }
        val sy = with(density) { dp.y.toPx() }
        if (sx in 0f..wPx && sy in 0f..hPx) return@BoxWithConstraints // on-screen → marker shows it

        // Clamp the centre→target ray onto the inset viewport rectangle.
        val dx = sx - cx
        val dy = sy - cy
        if (dx == 0f && dy == 0f) return@BoxWithConstraints
        var t = Float.MAX_VALUE
        if (dx > 0) t = minOf(t, (xMax - cx) / dx) else if (dx < 0) t = minOf(t, (xMin - cx) / dx)
        if (dy > 0) t = minOf(t, (yMax - cy) / dy) else if (dy < 0) t = minOf(t, (yMin - cy) / dy)
        if (t <= 0f || t == Float.MAX_VALUE) return@BoxWithConstraints
        val ex = cx + dx * t
        val ey = cy + dy * t
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

        val distM = ownLocation?.distanceToAsDouble(target)
        val distText = distM?.let { formatDistance(it) } ?: ""
        // Required glide ratio = horizontal distance / height above the target.
        // Only meaningful with a live altitude and a positive height margin.
        val glideText = if (ownAltitudeM != null && distM != null) {
            val margin = ownAltitudeM - (targetAltM ?: 0.0)
            if (margin > 1.0) "${String.format("%.1f", distM / margin)}:1" else "↓"
        } else ""

        val chipApproxHalfPx = with(density) { 64.dp.toPx() }
        val px = (ex - chipApproxHalfPx).roundToInt()
        val py = (ey - with(density) { 12.dp.toPx() }).roundToInt()

        Row(
            modifier = Modifier
                .offset { IntOffset(px.coerceIn(0, (wPx - 1).toInt()), py.coerceIn(0, (hPx - 1).toInt())) }
                .background(Color(0xCC0A1417), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("offscreen-waypoint-$label")
                .semantics { contentDescription = "offscreen-waypoint:$label:$distText" },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Canvas(Modifier.size(10.dp)) {
                rotate(angleDeg, pivot = center) {
                    val p = Path().apply {
                        moveTo(size.width, size.height / 2f)            // tip (points +x)
                        lineTo(size.width * 0.25f, size.height * 0.18f)
                        lineTo(size.width * 0.25f, size.height * 0.82f)
                        close()
                    }
                    drawPath(p, roleColor)
                }
            }
            Text(label, color = Color.White, fontSize = 10.sp, fontFamily = TernFontFamily.gruppo)
            if (distText.isNotEmpty()) {
                Text(distText, color = Color(0xFFB0B0B0), fontSize = 10.sp, fontFamily = TernFontFamily.gruppo)
            }
            if (glideText.isNotEmpty()) {
                Text(glideText, color = roleColor, fontSize = 10.sp, fontFamily = TernFontFamily.gruppo)
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()}m" else String.format("%.1fkm", meters / 1000.0)
