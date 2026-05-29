package com.ternparagliding.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// --- Colors (Aero Stealth Palette) ---
// Raw hex for use in non-compose code (Paints, XML)
const val AeroCharcoalHex = 0xFF121212
const val AeroSlateHex = 0xFF1E1E1E
const val AeroNeonCyanHex = 0xFF00E5FF
const val AeroCyanDarkHex = 0xFF00B0FF
const val AeroOrangeHex = 0xFFFF9100
const val AeroGlassHex = 0xCC1E1E1E

val AeroCharcoal = Color(AeroCharcoalHex)
val AeroSlate = Color(AeroSlateHex)
val AeroNeonCyan = Color(AeroNeonCyanHex)
val AeroCyanDark = Color(AeroCyanDarkHex)
val AeroOrange = Color(AeroOrangeHex)
val AeroGlass = Color(AeroGlassHex)
val Sleet = Color(0xFFB0BEC5)
val Mist = Color(0xFFECEFF1)

// --- Typography ---
val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

// --- Color Schemes ---
private val DarkColorScheme = darkColorScheme(
    primary = AeroNeonCyan,
    secondary = AeroCyanDark,
    tertiary = AeroOrange,
    background = AeroCharcoal,
    surface = AeroSlate,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = AeroSlate.copy(alpha = 0.7f),
    onSurfaceVariant = Sleet
)

private val LightColorScheme = lightColorScheme(
    primary = AeroCyanDark,
    secondary = AeroNeonCyan,
    tertiary = AeroOrange,
    background = Color.White,
    surface = Mist,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = AeroCharcoal,
    onSurface = AeroCharcoal,
    onSurfaceVariant = AeroCharcoal.copy(alpha = 0.7f)
)

@Composable
fun TernTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalTernTextStyles provides TernTextStyles(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
