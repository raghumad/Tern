package com.madanala.tern.overlay.airspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.expressions.dsl.feature as featureDsl

/**
 * Renders prioritized airspace candidates on a MapLibre map using
 * a single GeoJsonSource feeding a FillLayer (polygon fills) and a
 * LineLayer (polygon borders).
 *
 * Colour-coding by airspace class is done via MapLibre expressions
 * that read the "class" property on each GeoJSON feature. The GPU
 * does per-feature styling — no Kotlin loop, no per-polygon object.
 */
@Composable
fun AirspaceLayer(
    candidates: List<AirspaceCandidate>,
) {
    if (candidates.isEmpty()) return

    val featureCollection = remember(candidates) {
        AirspaceGeoJson.toFeatureCollection(candidates)
    }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(featureCollection),
    )

    val colorExpr = airspaceColorExpression()

    // Fill: translucent polygon interiors, colour-coded by class.
    org.maplibre.compose.layers.FillLayer(
        id = "airspace-fill",
        source = source,
        color = colorExpr,
        opacity = const(0.25f),
    )

    // Border: solid outlines so the airspace boundary is crisp.
    org.maplibre.compose.layers.LineLayer(
        id = "airspace-line",
        source = source,
        color = colorExpr,
        width = const(2.dp),
        opacity = const(0.8f),
    )
}

// ── Expression helpers ──────────────────────────────────────────────

// Standard ICAO airspace colours for paragliding:
// Controlled (A-E): blue.  Restricted: yellow.  Prohibited: orange.
// Danger: magenta.  Military: purple.  Unknown/fallback: red.
private val BLUE = Color(0xFF0000FF.toInt())
private val YELLOW = Color(0xFFFFFF00.toInt())
private val ORANGE = Color(0xFFFF8000.toInt())
private val MAGENTA = Color(0xFFFF00FF.toInt())
private val PURPLE = Color(0xFF8000FF.toInt())
private val RED = Color(0xFFFF0000.toInt())

/**
 * MapLibre expression: match on feature["class"] to pick colour.
 * Used for both fill and line — fill gets reduced opacity via
 * the opacity param, line gets near-full.
 */
@Suppress("UNCHECKED_CAST")
private fun airspaceColorExpression(): Expression<ColorValue> {
    val classExpr = featureDsl.get("class") as Expression<StringValue>
    return switch(
        classExpr,
        case("A", const(BLUE)),
        case("B", const(BLUE)),
        case("C", const(BLUE)),
        case("D", const(BLUE)),
        case("E", const(BLUE)),
        case("RESTRICTED", const(YELLOW)),
        case("PROHIBITED", const(ORANGE)),
        case("DANGER", const(MAGENTA)),
        case("MILITARY", const(PURPLE)),
        fallback = const(RED),
    )
}
