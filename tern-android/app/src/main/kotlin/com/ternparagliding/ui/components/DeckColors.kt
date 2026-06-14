package com.ternparagliding.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Flight-deck colour coding — one semantic palette so every instrument speaks the same language.
 * The deck encodes three independent dimensions in colour:
 *
 *  1. **Vertical air** (what matters most) — green = lift/up, red = sink/down, and your own value
 *     stays white at level (the dead-band). Drives the vario number, the trend bar, the track.
 *  2. **Your primary state** — white: own measured value (altitude, the climb digits) at max
 *     contrast; colour is reserved for the *sign* of vertical air, not decoration.
 *  3. **Reference & tactical** — cool blue for fixed geographic/nav references (launch, the
 *     altitude scale, wind); amber for attention / centering cues (thermal average). Units render
 *     in muted grey, subscript, so they disambiguate without competing with the number.
 *
 * Buddies keep their own freshness ramp (green→amber→orange→grey) from the Mezulla layer.
 */
object DeckColors {
    val lift = Color(0xFF22C55E)
    val sink = Color(0xFFEF4444)
    val neutral = Color(0xFF9CA3AF)      // glide / level / scale ticks
    val primary = Color(0xFFFFFFFF)      // your own value
    val reference = Color(0xFF60A5FA)    // launch, altitude scale, nav refs
    val wind = Color(0xFF93C5FD)
    val attention = Color(0xFFF59E0B)    // thermal average, alerts
    val unitColor = Color(0xFF8A92A6)    // subscript unit suffixes

    private const val DEADBAND_MS = 0.1

    /** Colour for a vertical-rate value by sign; level (within the dead-band) stays white. */
    fun vario(climbMs: Double?): Color = when {
        climbMs == null -> primary
        climbMs > DEADBAND_MS -> lift
        climbMs < -DEADBAND_MS -> sink
        else -> primary
    }

    /** Translucent panel background — low alpha so the moving map reads through the instruments. */
    fun panel(alpha: Float = 0.40f): Color = Color(0xFF0B0E14).copy(alpha = alpha)
}
