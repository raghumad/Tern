package com.ternparagliding.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.ui.theme.TernFontFamily
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// The rosette carries three unmistakable elements (see design-inflight-task-interactions.md):
//   • North      — the red carat (compass convention).
//   • Wind       — an AMBER arrow spanning the whole rosette, pointing downwind.
//   • Next WP    — a CYAN badge filling the rosette: bold number centred, a
//                  pointed tail rotated to the waypoint's bearing.
// Amber vs cyan are near-complements → maximum contrast; the small red carat is a
// distinct shape so the three never blur together.
private val WIND_COLOR = Color(0xFFFBBF24)      // amber
private val WIND_CASING = Color(0xCC0A1417)     // dark line under the wind arrow
private val WAYPOINT_COLOR = Color(0xFF1FE3DE)  // cyan
private val WAYPOINT_NUMBER = Color(0xFF06262B) // dark teal, bold, on the cyan badge
private val WAYPOINT_RING = Color(0xCC0A1417)   // dark casing around the badge

@Composable
fun Compass(
    rotation: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    /** Live wind direction (degrees the wind blows *from*); null hides the needle. */
    windFromDeg: Double? = null,
    /** Bearing (°true) from the pilot to the next waypoint; null hides the WP badge. */
    waypointBearingDeg: Double? = null,
    /** Short label centred on the WP badge (its task ordinal, e.g. "3"). */
    waypointLabel: String? = null,
    /** Tap-to-north: reset the map to north-up. Null = not tappable. */
    onTap: (() -> Unit)? = null,
) {
    val ringColor = MaterialTheme.colorScheme.outline
    // Standard compass convention: north carat is bright red.
    val northColor = Color(0xFFFF1A1A)

    val tapModifier = if (onTap != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null, // no ripple on the transparent rosette
            onClickLabel = "Reset to north",
            onClick = onTap,
        )
    } else Modifier

    // The ordinal is rendered OUTSIDE the rotated canvas, as a centred overlay, so it
    // (a) stays upright regardless of track-up rotation — never upside down — and
    // (b) sits ON TOP of the wind arrow instead of being crossed out by it.
    Box(
        modifier = modifier.size(size).then(tapModifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().rotate(rotation)) {
            val center = this.center
            val radius = this.size.minDimension / 2.0f

            // Outer ring
            drawCircle(ringColor, radius, center, style = Stroke(width = 4.dp.toPx()))

            // ── Next-waypoint badge: cyan disc + pointed tail to the bearing ──────
            // The canvas is rotated so up = north, so a true bearing maps straight to a
            // clockwise-from-up angle.
            if (waypointBearingDeg != null) {
                val th = Math.toRadians(waypointBearingDeg)
                val dx = sin(th).toFloat()
                val dy = (-cos(th)).toFloat()
                val badgeR = radius * 0.60f

                // Pointed tail: a triangle from near the badge edge out toward the rim.
                val tipX = center.x + radius * 0.97f * dx
                val tipY = center.y + radius * 0.97f * dy
                val baseX = center.x + badgeR * 0.80f * dx
                val baseY = center.y + badgeR * 0.80f * dy
                val perpX = -dy
                val perpY = dx
                val halfW = radius * 0.26f
                val tail = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(baseX + perpX * halfW, baseY + perpY * halfW)
                    lineTo(baseX - perpX * halfW, baseY - perpY * halfW)
                    close()
                }
                drawPath(tail, WAYPOINT_RING, style = Stroke(width = 3.dp.toPx())) // dark casing
                drawPath(tail, WAYPOINT_COLOR)

                // Cyan disc + dark casing ring.
                drawCircle(WAYPOINT_COLOR, badgeR, center)
                drawCircle(WAYPOINT_RING, badgeR, center, style = Stroke(width = 1.5.dp.toPx()))
            }

            // North-pointing carat (red), on top of the badge disc.
            val path = Path().apply {
                val baseY = center.y - radius * 0.4f
                moveTo(center.x, center.y - radius * 0.8f) // Top point
                lineTo(center.x - radius * 0.2f, baseY)    // Bottom-left
                lineTo(center.x + radius * 0.2f, baseY)    // Bottom-right
                close()
            }
            drawPath(path, northColor)

            // Live-wind arrow: an amber line spanning the whole rosette (rim → through
            // centre → opposite rim), arrowhead pointing downwind. A dark casing
            // underneath keeps it readable over the cyan badge and the map.
            if (windFromDeg != null) {
                val th = Math.toRadians(windFromDeg)
                val dx = sin(th).toFloat()       // toward the from-side (north = up)
                val dy = (-cos(th)).toFloat()
                val tail = Offset(center.x + radius * 0.92f * dx, center.y + radius * 0.92f * dy)
                val tip = Offset(center.x - radius * 0.92f * dx, center.y - radius * 0.92f * dy)
                val stroke = 3.dp.toPx()
                drawLine(WIND_CASING, tail, tip, strokeWidth = stroke + 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(WIND_COLOR, tail, tip, strokeWidth = stroke, cap = StrokeCap.Round)
                // Arrowhead at the downwind tip.
                val phi = atan2((tip.y - tail.y), (tip.x - tail.x))
                val headLen = radius * 0.30f
                for (s in intArrayOf(-1, 1)) {
                    val a = phi + Math.PI + s * 0.4
                    val end = Offset(tip.x + headLen * cos(a).toFloat(), tip.y + headLen * sin(a).toFloat())
                    drawLine(WIND_CASING, tip, end, strokeWidth = stroke + 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(WIND_COLOR, tip, end, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        }

        // Upright, on-top ordinal — never rotates, never crossed by the wind arrow.
        if (waypointBearingDeg != null && !waypointLabel.isNullOrBlank()) {
            Text(
                waypointLabel,
                color = WAYPOINT_NUMBER,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The next-waypoint readout that sits **under the compass** (design-locked): the
 * place name (description over cryptic code) + distance, so direction (rosette) and
 * identity/range read from a single spot — the pilot never glances at two corners.
 */
@Composable
fun NextWaypointReadout(
    name: String,
    distanceText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color(0xCC0A1417), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = WAYPOINT_COLOR,
            fontSize = 12.sp,
            fontFamily = TernFontFamily.gruppo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            distanceText,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = TernFontFamily.gruppo,
            maxLines = 1,
        )
    }
}
