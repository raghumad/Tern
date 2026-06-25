package com.ternparagliding.overlay.flight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ternparagliding.flight.FlightTrack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position

// Climb-tint palette — the trail IS a lift map.
private val LIFT_COLOR = Color(0xFF22C55E)    // green: climbing
private val SINK_COLOR = Color(0xFFEF4444)    // red: sinking
private val NEUTRAL_COLOR = Color(0xFF9CA3AF) // grey: neutral glide / unknown
private val TRACK_CASING = Color(0xCC0A1417)  // dark casing for contrast over the map

/**
 * Draws the pilot's climb-tinted flight track — the breadcrumb from [FlightTrack.segments].
 * Consecutive same-tint segments are merged into one polyline to keep the feature count low,
 * and a logger-gap break (non-contiguous segments) starts a fresh polyline so the trail
 * doesn't draw a phantom line across a dropout. [version] bumps when the track grows, keying
 * the GeoJSON rebuild. Call inside the `MaplibreMap { ... }` content lambda.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun FlightTrackLayer(segments: List<FlightTrack.Segment>, version: Int) {
    if (segments.isEmpty()) return

    val source = rememberGeoJsonSource(
        data = remember(version) { GeoJsonData.Features(com.ternparagliding.utils.geo.GeoJsonSafe.sanitize(trackLines(segments))) },
    )

    LineLayer(
        id = "flight-track-casing", source = source,
        color = const(TRACK_CASING), width = const(5.dp),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )

    val tintExpr = feature.get("tint") as Expression<StringValue>
    val colorExpr: Expression<ColorValue> = remember {
        switch(
            tintExpr,
            case(FlightTrack.TrackTint.LIFT.name, const(LIFT_COLOR)),
            case(FlightTrack.TrackTint.SINK.name, const(SINK_COLOR)),
            fallback = const(NEUTRAL_COLOR),
        )
    }
    LineLayer(
        id = "flight-track", source = source,
        color = colorExpr, width = const(3.dp),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
}

/** One polyline per run of same-tint, contiguous segments. */
private fun trackLines(segments: List<FlightTrack.Segment>): FeatureCollection<LineString, JsonObject> {
    val features = ArrayList<Feature<LineString, JsonObject>>()
    var start = 0
    while (start < segments.size) {
        val tint = segments[start].tint
        var end = start
        // Extend the run while tint matches AND the chain stays contiguous (no gap-break).
        while (end + 1 < segments.size &&
            segments[end + 1].tint == tint &&
            segments[end + 1].from == segments[end].to
        ) end++

        val positions = ArrayList<Position>(end - start + 2)
        positions.add(Position(segments[start].from.lon, segments[start].from.lat))
        for (i in start..end) positions.add(Position(segments[i].to.lon, segments[i].to.lat))
        features.add(
            Feature(LineString(positions), JsonObject(mapOf("tint" to JsonPrimitive(tint.name)))),
        )
        start = end + 1
    }
    return FeatureCollection(features)
}
