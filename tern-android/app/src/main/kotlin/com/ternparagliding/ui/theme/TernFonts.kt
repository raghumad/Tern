package com.ternparagliding.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ternparagliding.R

/**
 * Tern's font system. All font references go through [TernFontFamily]
 * so the actual .ttf can be swapped by changing ONE object.
 *
 * The current font is JetBrains Mono Nerd Font:
 * - Monospace (digits align for altitude/speed/time)
 * - 10,000+ icon glyphs (Material Design, Font Awesome, Weather Icons)
 * - Designed for screen readability at small sizes
 * - OFL license
 *
 * To swap to a different Nerd Font (or any font):
 * 1. Drop the new .ttf files in res/font/
 * 2. Update the Font() references below
 * 3. Everything else (theme, labels, overlays) picks up the change
 */
object TernFontFamily {
    val regular: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_nerd_regular, FontWeight.Normal),
    )
    val bold: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_nerd_bold, FontWeight.Bold),
    )
    val family: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_nerd_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_nerd_bold, FontWeight.Bold),
    )
}

/**
 * Text styles for different contexts in the app. Each style carries
 * the Nerd Font family so icon glyphs render correctly everywhere.
 *
 * Use via [LocalTernTextStyles] or [MaterialTheme.typography].
 */
data class TernTextStyles(
    /** Shadow for map labels — dark blur behind text for readability on any tile. */
    val mapShadow: Shadow = Shadow(
        color = Color.Black,
        offset = Offset(1f, 1f),
        blurRadius = 4f,
    ),

    /** Map overlay labels (peer callsign + staleness). Bold, glanceable. */
    val mapLabel: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 17.sp,
        shadow = mapShadow,
    ),

    /** Map overlay detail line (altitude, time, distance). Regular weight. */
    val mapDetail: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        shadow = mapShadow,
    ),

    /** Status bar text ("3 peers · Mezulla ● connected"). */
    val status: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        shadow = Shadow(color = Color.Black, offset = Offset(0.5f, 0.5f), blurRadius = 2f),
    ),

    /** View mode button label ("SAFETY", "CLIMB", "TACTICAL"). */
    val viewModeButton: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    ),

    /** SOS banner text. */
    val sosBanner: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    ),

    /** Metric numbers (altitude value, distance value, climb rate value). */
    val mapMetric: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        shadow = mapShadow,
    ),

    /** Unit suffixes (m, km, s, m/s, km/h). Smaller, lighter — disambiguation, not primary info. */
    val mapUnit: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        shadow = mapShadow,
    ),

    /** Directional arrows / bearing indicators. Sized to match the callsign. */
    val mapArrow: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        shadow = mapShadow,
    ),

    /** HUD / instrument readout (large, bold, glanceable at a distance). */
    val hud: TextStyle = TextStyle(
        fontFamily = TernFontFamily.family,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        shadow = Shadow(color = Color.Black, offset = Offset(1.5f, 1.5f), blurRadius = 6f),
    ),
)

/**
 * CompositionLocal so any composable can access [TernTextStyles]
 * without passing it as a parameter. Override in tests if needed.
 */
val LocalTernTextStyles = staticCompositionLocalOf { TernTextStyles() }
