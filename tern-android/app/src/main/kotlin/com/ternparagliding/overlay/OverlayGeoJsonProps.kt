package com.ternparagliding.overlay

/**
 * Shared accessors for a cached overlay feature's GeoJSON property bag.
 *
 * Cache features store the full GeoJSON, so human-facing fields live under a
 * nested `properties` map — not at the flat top level that
 * OverlayFeature.getStringProperty reads. These helpers look nested first,
 * then fall back to flat, and were factored out of the duplicated
 * `raw["properties"] as? Map ?: emptyMap()` / `props[k] ?: raw[k]` snippets in
 * PgSpotGeoJson and AirspaceGeoJson (Phase 0d dedup).
 */

/** The nested `properties` map of a raw cached feature, or empty. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.nestedProperties(): Map<String, Any> =
    (this["properties"] as? Map<String, Any>) ?: emptyMap()

/** A string property, resolved nested `properties.<key>` first, then flat `<key>`. */
fun Map<String, Any>.nestedOrFlatString(key: String): String? =
    (nestedProperties()[key] as? String) ?: (this[key] as? String)
