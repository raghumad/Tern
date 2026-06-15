package com.ternparagliding.ui.components

import androidx.compose.ui.graphics.Color
import com.ternparagliding.ui.theme.TernFontFamily

/**
 * Flight-deck design tokens — the **single place** to change how the deck instruments look.
 *
 * The deck deliberately keeps its own palette (separate from the app-wide Material [TernTheme]):
 * its colours are *flight-semantic* (lift/sink/attention), not Material roles. Change a colour here
 * and every instrument (HUD, tape, glyph, compass) follows. The deck **font** is one line away too —
 * [TernFontFamily.gruppo] in TernFonts.kt — and is re-exported as [font] so callers reference the
 * deck face by intent. Panel translucency is [panel].
 *
 * The deck encodes three independent dimensions in colour:
 *  1. **Vertical air** (what matters most) — [lift] = up, [sink] = down; your own value is white at
 *     level (the dead-band). Drives the vario number, the trend arc, the track.
 *  2. **Your primary state** — [value] (white): own measured readings at max contrast.
 *  3. **Reference & tactical** — [reference] (cool blue) for fixed nav refs (launch, scale, wind);
 *     [attention] (amber) for centering cues (thermal average).
 *
 * **Capsule-text rule:** instruments float on translucent panels over a moving map, so muted greys
 * vanish over bright tiles. *All* capsule text is therefore one of two bright tones — [value] for
 * primary readings, [label] for secondary text (row labels + unit suffixes), which is a touch
 * quieter so it doesn't compete with the number but stays clearly legible.
 *
 * Buddies keep their own freshness ramp (green→amber→orange→grey) from the Mezulla layer.
 */
object DeckColors {
    // ── Flight-semantic ──
    val lift = Color(0xFF22C55E)
    val sink = Color(0xFFEF4444)
    val attention = Color(0xFFF59E0B)    // thermal average, alerts
    val reference = Color(0xFF60A5FA)    // launch, altitude scale, nav refs
    val wind = Color(0xFF93C5FD)

    // ── Capsule text (always bright — see the capsule-text rule above) ──
    val value = Color(0xFFFFFFFF)        // primary readings / your own value
    val label = Color(0xFFD3D9E2)        // row labels + unit suffixes (bright, slightly quieter)

    // Aliases so existing call sites read by intent.
    val primary = value
    val neutral = value
    val unitColor = label

    /** The deck instrument typeface. Swap the whole deck's font in [TernFontFamily.gruppo]. */
    val font = TernFontFamily.gruppo

    private const val DEADBAND_MS = 0.1

    /** Colour for a vertical-rate value by sign; level (within the dead-band) stays white. */
    fun vario(climbMs: Double?): Color = when {
        climbMs == null -> value
        climbMs > DEADBAND_MS -> lift
        climbMs < -DEADBAND_MS -> sink
        else -> value
    }

    /** Translucent panel background — low alpha so the moving map reads through the instruments. */
    fun panel(alpha: Float = 0.40f): Color = Color(0xFF0B0E14).copy(alpha = alpha)
}
