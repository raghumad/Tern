package com.ternparagliding.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ternparagliding.R

/** nf-fa-flag (U+F024) — the one glyph Tern uses to mean "waypoint". */
private val WAYPOINT_FLAG = String(Character.toChars(0xF024))

/** Violet the map paints waypoints with ([com.ternparagliding.overlay.waypoint.WaypointLibraryLayer]). */
val WaypointViolet = Color(com.ternparagliding.overlay.waypoint.WAYPOINT_VIOLET)

/**
 * The waypoint flag glyph, drawn with the same Nerd Font character the map marker uses
 * ([com.ternparagliding.overlay.waypoint.renderWaypointMarkerBitmap]). Use it anywhere a
 * waypoint is iconified — the dock, page headers, menu rows — so the symbol reads the same
 * on the map and everywhere else, the way the pilot already learned it.
 */
@Composable
fun WaypointGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    fontSize: TextUnit = 18.sp,
) {
    val nerd = remember { FontFamily(Font(R.font.jetbrains_mono_nerd_regular)) }
    Text(
        text = WAYPOINT_FLAG,
        fontFamily = nerd,
        color = if (tint == Color.Unspecified) LocalContentColor.current else tint,
        fontSize = fontSize,
        modifier = modifier,
    )
}
