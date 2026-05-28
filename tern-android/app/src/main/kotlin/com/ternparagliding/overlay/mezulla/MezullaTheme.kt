package com.ternparagliding.overlay.mezulla

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.ui.theme.AeroNeonCyan
import com.ternparagliding.ui.theme.AeroCyanDark
import com.ternparagliding.ui.theme.AeroOrange
import com.ternparagliding.ui.theme.AeroCharcoal
import com.ternparagliding.ui.theme.AeroGlass
import com.ternparagliding.ui.theme.Sleet

/**
 * Visual constants for Mezulla peer markers and UI elements.
 * Derives from Tern's Aero Stealth palette (ui/theme/Theme.kt).
 * One place to adjust all Mezulla-specific visuals.
 */
object MezullaTheme {

    // -- Staleness thresholds (seconds) --
    const val FRESH_THRESHOLD_SECONDS = 30L
    const val AGING_THRESHOLD_SECONDS = 120L   // 2 minutes
    const val STALE_THRESHOLD_SECONDS = 300L   // 5 minutes

    // -- Staleness colors --
    object StalenessColors {
        val fresh = AeroNeonCyan            // bright cyan — "I know where they are"
        val aging = AeroOrange              // amber — "data is getting old"
        val stale = Color(0xFFFF5722)       // deep orange — "haven't heard in a while"
        val lost  = Sleet                   // muted gray — "lost contact"
    }

    // -- Staleness opacity --
    object StalenessOpacity {
        const val fresh = 1.0f
        const val aging = 0.8f
        const val stale = 0.5f
        const val lost = 0.3f
    }

    // -- Circle marker (on the map) --
    object Circle {
        val fillColor = AeroCyanDark
        val strokeColor = Color.White
        val radius: Dp = 4.dp
        val strokeWidth: Dp = 1.5.dp
    }

    // -- Text label (on the map, next to circles) --
    object Label {
        val color = Color.White
        val haloColor = AeroCharcoal.copy(alpha = 0.8f)
        val haloWidth: Dp = 1.5.dp
        val size: TextUnit = 13.sp
    }

    // -- SOS alert --
    object Sos {
        val bannerBackground = Color(0xFFD32F2F) // Material red-700
        val bannerText = Color.White
        val iconColor = Color.White
        val markerPulseColor = Color(0xFFFF1744) // red accent
    }

    // -- Status indicator ("Mezulla ● connected / off") --
    object Status {
        val connectedDotColor = AeroNeonCyan
        val disconnectedDotColor = Sleet
        val textColor = Color.White
        val subtleTextColor = Sleet
    }

    // -- View mode button --
    object ViewModeButton {
        val background = AeroGlass
        val textColor = Color.White
        val minSize: Dp = 64.dp             // glove-friendly
    }

    // -- Sizing --
    object Sizing {
        val touchTargetMin: Dp = 64.dp      // glove-friendly minimum
        val mapPadding: Dp = 16.dp
        val bannerPadding: Dp = 12.dp
    }
}
