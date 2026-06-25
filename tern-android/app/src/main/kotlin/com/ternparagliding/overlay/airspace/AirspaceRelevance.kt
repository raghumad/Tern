package com.ternparagliding.overlay.airspace

/**
 * Altitude-aware airspace **relevance** — the safety half of "airspace as an instrument".
 *
 * A block floored far above you is not a threat right now; one you're inside or climbing
 * toward is. This turns the pilot's live altitude + each airspace's floor/ceiling into a
 * single emphasis level the renderer styles on, so far-above blocks recede and what can
 * actually bite you stands out. Pure + Android-free so it's unit-tested directly.
 *
 * When there's no live altitude (on the ground / no GPS fix) it falls back to a static
 * class emphasis (the planning-time declutter), so the map is never blank or misleading.
 */
enum class AirspaceEmphasis { BOLD, NORMAL, FAINT }

/** A parsed vertical limit in feet, plus whether it's ground-referenced (AGL/SFC). */
data class LimitFt(val feet: Double, val ground: Boolean)

object AirspaceRelevance {

    /** Within this vertical distance (ft) of a slab you're "approaching" it. ~1000 ft. */
    const val NEAR_MARGIN_FT = 1000.0

    private const val M_TO_FT = 3.28084

    /** Hard classes that always read bold when relevant — they bite hardest. */
    private val HARD = setOf("CTR", "PROHIBITED", "RESTRICTED", "DANGER")
    private val CONTROLLED = setOf("A", "B", "C", "D")

    /**
     * Parse an OpenAIP vertical-limit object (`{value, unit, referenceDatum}`) into feet.
     * unit: 0 = m, 6 = FL (already feet here, e.g. 13000), else feet. ref: 0 = GND (AGL),
     * 1 = MSL, 2 = STD (treated as MSL for relevance). Returns null when unparseable.
     */
    fun parseLimitFeet(limitObj: Any?): LimitFt? {
        val map = limitObj as? Map<*, *> ?: return null
        val value = (map["value"] as? Number)?.toDouble() ?: return null
        val unit = (map["unit"] as? Number)?.toInt() ?: 1
        val ref = (map["referenceDatum"] as? Number)?.toInt() ?: 1
        val feet = if (unit == 0) value * M_TO_FT else value // FL & ft are already feet
        return LimitFt(feet = feet, ground = ref == 0)
    }

    /** Static, altitude-free emphasis by class — the planning-time fallback. */
    fun classEmphasis(airspaceClass: String): AirspaceEmphasis = when (airspaceClass) {
        in HARD -> AirspaceEmphasis.BOLD
        in CONTROLLED -> AirspaceEmphasis.NORMAL
        else -> AirspaceEmphasis.FAINT // E / F / unknown — advisory
    }

    /**
     * The emphasis for one airspace given the pilot's live altitude (ft MSL, null if
     * unknown). Inside the slab → BOLD; within [NEAR_MARGIN_FT] of it → NORMAL; clearly
     * above the ceiling or below the floor → FAINT (recede). A ground-referenced floor is
     * treated as reaching the surface, so you're "inside" whenever you're below the ceiling.
     */
    fun emphasisFor(
        airspaceClass: String,
        floorFtMsl: Double?,
        ceilingFtMsl: Double?,
        floorIsGround: Boolean,
        pilotFtMsl: Double?,
        nearMarginFt: Double = NEAR_MARGIN_FT,
    ): AirspaceEmphasis {
        if (pilotFtMsl == null) return classEmphasis(airspaceClass)

        val floor = if (floorIsGround) Double.NEGATIVE_INFINITY else (floorFtMsl ?: Double.NEGATIVE_INFINITY)
        val ceiling = ceilingFtMsl ?: Double.POSITIVE_INFINITY

        return when {
            pilotFtMsl in floor..ceiling -> AirspaceEmphasis.BOLD
            pilotFtMsl in (floor - nearMarginFt)..(ceiling + nearMarginFt) -> AirspaceEmphasis.NORMAL
            else -> AirspaceEmphasis.FAINT
        }
    }
}
