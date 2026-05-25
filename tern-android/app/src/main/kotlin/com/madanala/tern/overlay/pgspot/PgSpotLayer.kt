package com.madanala.tern.overlay.pgspot

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * MapLibre Compose layer that renders PG spots as text symbols.
 *
 * Data flow: PGSpotCache -> OverlayPrioritizer -> [overlayFeaturesToGeoJson] -> here.
 *
 * The caller passes a [FeatureCollection] (produced by [overlayFeaturesToGeoJson]).
 * This composable creates a GeoJsonSource + SymbolLayer. MapLibre handles
 * collision detection and auto-hides cluttered labels (textAllowOverlap=false).
 *
 * Weather display is NOT in scope — it's a separate concern that will
 * decorate these symbols or add a sibling layer when ready.
 */
@Composable
fun PgSpotLayer(
    featureCollection: FeatureCollection<Geometry, JsonObject>,
) {
    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(featureCollection),
    )

    @Suppress("UNCHECKED_CAST")
    val nameExpr = feature.get("name") as Expression<StringValue>

    // The SymbolLayer composable function in maplibre-compose 0.13.0 is
    // shadowed by an internal class of the same name. Using the FQN
    // resolves to the public function (the class is invisible to us).
    org.maplibre.compose.layers.SymbolLayer(
        id = "pg-spot-symbols",
        source = source,
        textField = format(span(nameExpr)),
        textSize = const(12.sp),
        textColor = const(Color(0xFF1B5E20)),
        textHaloColor = const(Color.White),
        textHaloWidth = const(1.dp),
        textAllowOverlap = const(false),
        textOffset = offset(0.sp, 1.2.sp),
    )
}
