package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.R
import com.ternparagliding.model.LocationType

/**
 * The waypoint's role marker, drawn to match exactly what the map shows
 * ([com.ternparagliding.overlay.task.TaskLayer] / `renderWaypointBitmap`): a role-coloured
 * disc with a white ring and a short code — the turnpoint sequence for turnpoints, a letter
 * for special roles (T/S/E/LZ), and the checkered-flag glyph for a GOAL. Reusing the map's
 * [com.ternparagliding.overlay.task.waypointStyle] keeps the list, the editor, and the map
 * reading as one language so the pilot doesn't have to re-learn icons between surfaces.
 *
 * [seq] is the turnpoint sequence number — only used for [LocationType.TURNPOINT]; pass 0 (or
 * anything) for the lettered roles.
 */
@Composable
fun WaypointMarkerBadge(
    type: LocationType,
    seq: Int,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    val style = remember(type, seq) { com.ternparagliding.overlay.task.waypointStyle(type, seq) }
    val isGoal = type == LocationType.GOAL
    val nerdFamily = remember(isGoal) {
        if (isGoal) FontFamily(Font(R.font.jetbrains_mono_nerd_regular)) else null
    }
    // White ring (the map marker's white outline) → padding → role disc → centred code.
    Box(
        modifier = modifier
            .size(size)
            .background(Color.White, CircleShape)
            .padding(2.dp)
            .background(Color(style.discColor), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isGoal && nerdFamily != null) {
            Text(
                text = GOAL_FLAG_GLYPH,
                color = Color.White,
                fontFamily = nerdFamily,
                fontSize = (size.value * 0.46f).sp,
            )
        } else {
            Text(
                text = style.code,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * if (style.code.length >= 2) 0.34f else 0.44f).sp,
                maxLines = 1,
            )
        }
    }
}

/** nf-fa-flag-checkered (U+F11E) — the same GOAL glyph the map marker draws. */
private val GOAL_FLAG_GLYPH = String(Character.toChars(0xF11E))
