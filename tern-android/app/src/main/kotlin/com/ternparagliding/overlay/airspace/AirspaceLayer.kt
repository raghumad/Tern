package com.ternparagliding.overlay.airspace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.expressions.dsl.feature as featureDsl

/**
 * Bitmap supersampling factor — keeps the labels crisp when scaled by zoom.
 */
private const val S = 2.0f

/**
 * Renders prioritized airspace candidates on a MapLibre map using
 * a single GeoJsonSource feeding a FillLayer (polygon fills), a
 * LineLayer (polygon borders), and a SymbolLayer (at-a-glance labels).
 */
@Composable
fun AirspaceLayer(
    featureCollection: FeatureCollection<Geometry, JsonObject>,
) {
    if (featureCollection.features.isEmpty()) return

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(com.ternparagliding.utils.geo.GeoJsonSafe.sanitize(featureCollection)),
    )

    val colorExpr = airspaceColorExpression()

    // Fill: translucent interiors, but only once you've zoomed IN — at region zoom the
    // map showed a flat 25% fill for every footprint, which blanketed dense regions
    // (an instrument that's always red is no instrument). Advisory classes (E / unknown)
    // stay outline-only at every zoom. The boundary line carries the low-zoom read.
    org.maplibre.compose.layers.FillLayer(
        id = "airspace-fill",
        source = source,
        color = colorExpr,
        opacity = fillOpacityExpression(),
    )

    // Border: always drawn (the boundary is the at-a-glance instrument), emphasised by the
    // altitude-aware "emphasis" stamp — BOLD what you're in/approaching, FAINT what's floored
    // far above (or, on the ground, by class). Width reinforces it.
    org.maplibre.compose.layers.LineLayer(
        id = "airspace-line",
        source = source,
        color = colorExpr,
        width = lineWidthExpression(),
        opacity = lineOpacityExpression(),
    )

    // Labels: At-a-glance class + limits.
    AirspaceLabels(featureCollection, source)
}

/**
 * Renders labels at the centroid of each airspace polygon.
 * Since raster styles lack glyphs, we rasterise text to icon bitmaps.
 */
@Composable
private fun AirspaceLabels(
    featureCollection: FeatureCollection<Geometry, JsonObject>,
    source: org.maplibre.compose.sources.GeoJsonSource,
) {
    val labels = remember(featureCollection) {
        featureCollection.features
            .mapNotNull { (it.properties?.get("label") as? JsonPrimitive)?.content }
            .distinct()
    }

    if (labels.isEmpty()) return

    @Suppress("UNCHECKED_CAST")
    val labelExpr = featureDsl.get("label") as Expression<StringValue>

    val iconImage = remember(labels) {
        val transparent = image(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        )
        val cases = labels.map { label ->
            case(label, image(renderAirspaceLabelBitmap(label).asImageBitmap()))
        }.toTypedArray()
        switch(labelExpr, *cases, fallback = transparent)
    }

    // Hide the floor/ceiling labels until you've zoomed in — at region zoom they stacked
    // on top of each other (and on the PG-spot names), which was the worst of the clutter.
    val iconSize = step(
        zoom(),
        const(0.0f),
        10.0 to const(0.85f),
        13.0 to const(1.0f),
    )

    org.maplibre.compose.layers.SymbolLayer(
        id = "airspace-labels",
        source = source,
        iconImage = iconImage,
        iconSize = iconSize,
        iconAllowOverlap = const(false),
    )
}

/**
 * Rasterises a two-line airspace label (e.g. "CLASS B\nSFC / 5000ft").
 */
private fun renderAirspaceLabelBitmap(text: String): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 11f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    
    val lines = text.split('\n')
    val maxW = lines.maxOf { paint.measureText(it) }
    val lineH = paint.descent() - paint.ascent()
    val totalH = lineH * lines.size + (lines.size - 1) * 2f * S

    val padH = 8f * S
    val padV = 4f * S
    val w = maxW + padH * 2
    val h = totalH + padV * 2

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    
    var y = padV - paint.ascent()
    lines.forEachIndexed { index, line ->
        if (index == 0) {
            paint.color = AndroidColor.YELLOW // Highlight Class
        } else {
            paint.color = AndroidColor.WHITE
        }
        // Simple dark halo for contrast without the pill background
        paint.setShadowLayer(2f * S, 0f, 0f, AndroidColor.BLACK)
        canvas.drawText(line, w / 2f, y, paint)
        y += lineH + 2f * S
    }
    
    return bmp
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
/**
 * Fill opacity: zoom-gated so region view is outline-only (no blanket fill that turns dense
 * regions into wallpaper), ramping in only once you've zoomed to where a translucent fill is
 * readable. A pure zoom step — nesting a class switch around it stops the expression
 * evaluating (the fill snaps to opaque), so class emphasis lives on the border line instead.
 */
private fun fillOpacityExpression(): Expression<FloatValue> =
    step(zoom(), const(0.0f), 10.0 to const(0.07f), 12.0 to const(0.10f))

/**
 * Border opacity by the altitude-aware emphasis stamp ([AirspaceGeoJson.withEmphasis]):
 * BOLD what you're in/approaching, FAINT what's floored far above you (recedes), NORMAL
 * between. On the ground (no altitude) the stamp falls back to a static class emphasis.
 */
@Suppress("UNCHECKED_CAST")
private fun lineOpacityExpression(): Expression<FloatValue> {
    val emphasis = featureDsl.get("emphasis") as Expression<StringValue>
    return switch(
        emphasis,
        case("BOLD", const(0.95f)),
        case("FAINT", const(0.2f)),
        fallback = const(0.5f), // NORMAL
    )
}

/** Border width reinforces the emphasis — bold boundaries thicker, receded ones hairline. */
@Suppress("UNCHECKED_CAST")
private fun lineWidthExpression() =
    switch(
        featureDsl.get("emphasis") as Expression<StringValue>,
        case("BOLD", const(3.dp)),
        case("FAINT", const(1.5.dp)),
        fallback = const(2.dp), // NORMAL
    )

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
        case("CTR", const(PURPLE)),
        fallback = const(RED),
    )
}
