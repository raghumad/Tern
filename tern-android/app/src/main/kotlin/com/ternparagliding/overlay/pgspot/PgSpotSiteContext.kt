package com.ternparagliding.overlay.pgspot

import com.ternparagliding.overlay.nestedOrFlatNumber
import com.ternparagliding.weather.Octant
import com.ternparagliding.weather.SiteContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bridges a cached PG-spot's raw Paragliding Earth properties into a
 * [SiteContext] the Flyability engine can reason over. PGE gives a launch's
 * `takeoff_altitude` (m) and eight orientation octants (`N`…`NW`, scored 0/1/2 =
 * no/workable/ideal) — the geometry that lets us answer "is the wind on *this*
 * hill" and "where is cloudbase relative to *this* launch". The weather package
 * stays vendor-agnostic; this is where the PGE schema is known.
 *
 * Robust to missing fields: an absent altitude → null (unknown), absent octants →
 * an empty map (orientation simply isn't judged). Mirrors the nested-or-flat
 * property pattern the rest of the overlay code uses.
 */
fun siteContextOf(feature: Map<String, Any>): SiteContext {
    // PGE altitudes are metres MSL; 0/absent means "not recorded", not sea level.
    val elevation = feature.nestedOrFlatNumber("takeoff_altitude")?.takeIf { it > 0.0 }
    val orientations = buildMap {
        for (oct in Octant.entries) {
            feature.nestedOrFlatNumber(oct.name)?.toInt()?.let { put(oct, it) }
        }
    }
    return SiteContext(elevationM = elevation, orientations = orientations)
}

/** Octant scores + elevation serialized onto a MapLibre feature, ready for [siteContextOf]. */
fun SiteContext.toMapLibreProps(): Map<String, JsonPrimitive> = buildMap {
    elevationM?.let { put("elevationM", JsonPrimitive(it)) }
    orientations.forEach { (oct, score) -> put(oct.name, JsonPrimitive(score)) }
}

/**
 * Rebuild a [SiteContext] from a tapped MapLibre feature's properties — the inverse
 * of [toMapLibreProps]. Tap handlers only see the on-map feature, so the launch
 * geometry travels there as flat numeric props.
 */
fun siteContextFromJsonProps(props: JsonObject?): SiteContext {
    if (props == null) return SiteContext()
    fun num(key: String): Double? = (props[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()
    val elevation = num("elevationM")?.takeIf { it > 0.0 }
    val orientations = buildMap {
        for (oct in Octant.entries) num(oct.name)?.toInt()?.let { put(oct, it) }
    }
    return SiteContext(elevationM = elevation, orientations = orientations)
}
