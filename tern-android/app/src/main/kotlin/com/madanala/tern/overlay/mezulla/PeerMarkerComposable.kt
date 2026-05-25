package com.madanala.tern.overlay.mezulla

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import com.madanala.tern.ui.theme.LocalTernTextStyles
import com.madanala.tern.ui.theme.TernTextStyles

/**
 * A single peer marker rendered as a quadrant layout:
 *
 *          ↗ COR                ← TOP: bearing arrow + callsign
 *     5ˢ   󰀂   1915ᵐ          ← LEFT: primary metric, CENTER: icon, RIGHT: secondary
 *
 * The icon sits at the geographic position. Information fans out.
 * Text sizes follow the instrument hierarchy:
 * - Callsign: large bold (identity)
 * - Metric values: medium bold (the numbers you read)
 * - Unit suffixes: small light (disambiguation, not primary info)
 */
@Composable
fun PeerMarker(
    callsign: String,
    icon: String,
    iconColor: Color,
    anim: PeerIconAnimation,
    leftValue: String,
    leftUnit: String,
    rightValue: String,
    rightUnit: String,
    bearingDegrees: Float? = null,
    leftColor: Color = Color.White,
    rightColor: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    val styles = LocalTernTextStyles.current

    Box(
        modifier = modifier
            .testTag("peer_marker_${callsign.lowercase()}")
            .border(1.dp, iconColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        // CENTER: icon (the anchor at the geographic position)
        Text(
            text = icon,
            color = iconColor.copy(alpha = anim.alpha),
            style = styles.mapLabel.copy(fontSize = styles.mapLabel.fontSize * 1.3f),
            modifier = Modifier.graphicsLayer {
                rotationZ = anim.rotation
                scaleX = anim.scale
                scaleY = anim.scale
            },
        )

        // TOP: bearing arrow + callsign
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp),
        ) {
            if (bearingDegrees != null) {
                Text(
                    text = MezullaIcons.NAVIGATION,
                    color = Color.White.copy(alpha = 0.7f),
                    style = styles.mapArrow,
                    modifier = Modifier.rotate(bearingDegrees),
                )
            }
            Text(
                text = callsign.uppercase(),
                color = Color.White,
                style = styles.mapLabel,
            )
        }

        // LEFT: primary metric (value + unit)
        MetricText(
            value = leftValue,
            unit = leftUnit,
            color = leftColor.copy(alpha = anim.alpha),
            styles = styles,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-6).dp),
        )

        // RIGHT: secondary metric (value + unit)
        MetricText(
            value = rightValue,
            unit = rightUnit,
            color = rightColor.copy(alpha = anim.alpha),
            styles = styles,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 6.dp),
        )
    }
}

/**
 * A metric value with a subscript-sized unit suffix.
 * "1915" in medium bold + "m" in small light.
 */
@Composable
fun MetricText(
    value: String,
    unit: String,
    color: Color,
    styles: TernTextStyles,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        Text(
            text = value,
            color = color,
            style = styles.mapMetric,
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                color = color.copy(alpha = 0.6f),
                style = styles.mapUnit.copy(
                    baselineShift = BaselineShift(0.1f),
                ),
            )
        }
    }
}

/**
 * A climb/sink indicator: colored arrow + value + unit.
 * Green ↑2.3 m/s  or  Red ↓1.5 m/s
 */
@Composable
fun ClimbMetricText(
    value: Double,
    unit: String,
    styles: TernTextStyles,
    modifier: Modifier = Modifier,
) {
    val isClimbing = value >= 0
    val arrow = if (isClimbing) MezullaIcons.ARROW_UP else MezullaIcons.ARROW_DOWN
    val color = if (isClimbing) MezullaTheme.StalenessColors.fresh else Color(0xFFF44336)
    val displayValue = String.format("%.1f", kotlin.math.abs(value))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text = arrow,
            color = color,
            style = styles.mapArrow.copy(fontSize = styles.mapMetric.fontSize),
        )
        Text(
            text = displayValue,
            color = color,
            style = styles.mapMetric,
        )
        Text(
            text = unit,
            color = color.copy(alpha = 0.6f),
            style = styles.mapUnit,
        )
    }
}

/**
 * Altitude relative to the pilot: ▲120m (above) or ▼340m (below).
 */
@Composable
fun RelativeAltText(
    deltaMeters: Int,
    styles: TernTextStyles,
    modifier: Modifier = Modifier,
) {
    val isAbove = deltaMeters >= 0
    val arrow = if (isAbove) "▲" else "▼"
    val color = if (isAbove) MezullaTheme.StalenessColors.fresh else Color(0xFFF44336)

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        Text(
            text = arrow,
            color = color,
            style = styles.mapMetric,
        )
        Text(
            text = "${kotlin.math.abs(deltaMeters)}",
            color = color,
            style = styles.mapMetric,
        )
        Text(
            text = "m",
            color = color.copy(alpha = 0.6f),
            style = styles.mapUnit,
        )
    }
}
