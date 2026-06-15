package com.ternparagliding.overlay.thermal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

// Heat ramp — warmer = more reliable thermal. Stroked for contrast over any terrain.
private val HI_COLOR = Color(0xFFEF4444)    // red — most reliable
private val MID_COLOR = Color(0xFFF59E0B)   // amber
private val LOW_COLOR = Color(0xFFFCD34D)   // pale yellow
private val STROKE = Color(0xCC0A1417)

/**
 * Renders kk7 thermal hotspots as colour-coded dots (a CircleLayer), warmer = more reliable. Cheap
 * to draw (no bitmaps) and declutters naturally by shrinking at regional zoom.
 */
@Composable
fun ThermalHotspotLayer(featureCollection: FeatureCollection<Geometry, JsonObject>) {
    val source = rememberGeoJsonSource(data = GeoJsonData.Features(featureCollection))

    @Suppress("UNCHECKED_CAST")
    val bucket = feature.get("bucket") as Expression<StringValue>
    val colorExpr: Expression<ColorValue> = remember {
        switch(
            bucket,
            case(ThermalBucket.HI.name, const(HI_COLOR)),
            case(ThermalBucket.MID.name, const(MID_COLOR)),
            fallback = const(LOW_COLOR),
        )
    }
    // Zoom-aware radius: small dots at regional zoom, larger when zoomed in.
    val radius = step(zoom(), const(2.5.dp), 8.0 to const(4.dp), 12.0 to const(6.dp))

    CircleLayer(
        id = "thermal-hotspots",
        source = source,
        color = colorExpr,
        radius = radius,
        opacity = const(0.85f),
        strokeColor = const(STROKE),
        strokeWidth = const(1.dp),
    )
}
