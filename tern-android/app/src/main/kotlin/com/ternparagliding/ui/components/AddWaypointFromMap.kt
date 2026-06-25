package com.ternparagliding.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Cockpit palette — same neon cyan the task line uses.
private val RETICLE_CYAN = Color(0xFF00E5FF)

/**
 * The centre-pin **crosshair** for add-from-map mode. Pin the map under it (pan so
 * the target sits at screen-centre) and the action bar drops a point exactly there —
 * the pilot never has to tap an obscured spot. Drawn at the map's geometric centre,
 * which is `cameraState.position.target`, so the visual and the dropped coordinate
 * agree.
 */
@Composable
fun AddWaypointReticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(88.dp).testTag("AddWaypointReticle")) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val gap = r * 0.30f          // open centre so the exact point stays visible
        val arm = r * 0.94f
        val ringR = r * 0.30f
        val shadow = Color(0xB3000000)

        // A dark under-stroke first so the crosshair reads over light *and* busy terrain.
        fun cross(color: Color, w: Float) {
            // horizontal arms
            drawLine(color, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), strokeWidth = w)
            drawLine(color, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), strokeWidth = w)
            // vertical arms
            drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), strokeWidth = w)
            drawLine(color, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), strokeWidth = w)
            // centre ring
            drawCircle(color, ringR, c, style = Stroke(width = w))
        }
        cross(shadow, 10f)
        cross(RETICLE_CYAN, 4f)
        // a dark-haloed solid dot dead-centre marks the exact drop point
        drawCircle(shadow, 4.5f, c)
        drawCircle(RETICLE_CYAN, 3f, c)
    }
}

/**
 * The bottom action bar shown in add-from-map mode: a one-line hint, a **Done** out,
 * and the primary **Add point here** which drops a point at the crosshair. Stays up
 * across drops so several points can be placed in a row.
 */
@Composable
fun AddWaypointBar(
    pointCount: Int,
    onAddHere: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("AddWaypointBar"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = if (pointCount == 0) "Centre the crosshair on a waypoint to add it — or on empty map to drop a new one"
                else "Keep going — centre a waypoint or empty map, then add ($pointCount point${if (pointCount == 1) "" else "s"} so far)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.testTag("AddWaypointDone"),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onAddHere,
                    modifier = Modifier.weight(1f).testTag("AddPointHere"),
                    colors = ButtonDefaults.buttonColors(containerColor = RETICLE_CYAN, contentColor = Color.Black),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add point here", fontWeight = FontWeight.Black, letterSpacing = 0.3.sp)
                }
            }
        }
    }
}
