package com.madanala.tern.overlay.mezulla

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.madanala.tern.ui.theme.LocalTernTextStyles
import com.madanala.tern.ui.theme.TernTextStyles

/**
 * Peer marker as a 3×3 grid table. One border, one scale.
 * Resize the table → everything inside resizes.
 *
 *  ┌──────────┬──────────┬──────────┐
 *  │          │   COR    │          │  top-center: callsign
 *  ├──────────┼──────────┼──────────┤
 *  │   5ˢ    │    󰀂    │  1915ᵐ  │  middle: metric | icon | metric
 *  ├──────────┼──────────┼──────────┤
 *  │          │          │          │  bottom: available for future use
 *  └──────────┴──────────┴──────────┘
 *
 * Scale is computed from map zoom level so the label
 * stays proportional to the map's own text.
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
    leftColor: Color = Color.White,
    rightColor: Color = Color.White,
    zoomScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val styles = LocalTernTextStyles.current
    val borderColor = iconColor.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .testTag("peer_marker_${callsign.lowercase()}")
            .scale(zoomScale)
            .border(0.5.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Row 1 (top): callsign
            Text(
                text = callsign.uppercase(),
                color = Color.White,
                style = styles.mapLabel,
                textAlign = TextAlign.Center,
            )

            // Row 2 (middle): left metric | icon | right metric
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Left metric
                MetricText(
                    value = leftValue,
                    unit = leftUnit,
                    color = leftColor.copy(alpha = anim.alpha),
                    styles = styles,
                    modifier = Modifier.widthIn(min = 28.dp),
                    align = TextAlign.End,
                )

                // Center icon
                Text(
                    text = icon,
                    color = iconColor.copy(alpha = anim.alpha),
                    style = styles.mapLabel,
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .graphicsLayer {
                            rotationZ = anim.rotation
                            scaleX = anim.scale
                            scaleY = anim.scale
                        },
                )

                // Right metric
                MetricText(
                    value = rightValue,
                    unit = rightUnit,
                    color = rightColor.copy(alpha = anim.alpha),
                    styles = styles,
                    modifier = Modifier.widthIn(min = 28.dp),
                    align = TextAlign.Start,
                )
            }
        }
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
    align: TextAlign = TextAlign.Start,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        if (align == TextAlign.End) {
            // Push content to the right
            Box(Modifier.weight(1f))
        }
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
                    baselineShift = BaselineShift(0.15f),
                ),
            )
        }
        if (align == TextAlign.Start) {
            Box(Modifier.weight(1f))
        }
    }
}
