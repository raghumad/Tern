package com.ternparagliding.overlay.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.model.Route
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

// Tern cockpit palette -- route line is neon cyan, labels are white on charcoal.
private val ROUTE_LINE_COLOR = Color(0xFF00E5FF)
private val LABEL_TEXT_COLOR = Color.White
private val LABEL_HALO_COLOR = Color(0xFF121212)

/**
 * Renders the active route on a MapLibre map.
 *
 * - **LineLayer** draws the route polyline (always visible -- not
 *   budgeted, because it's one object and it's navigation).
 * - **SymbolLayer** draws waypoint markers with name + type labels.
 *   Waypoint markers participate in the overlay budget via
 *   [RouteWaypointCandidate], but the visual layer itself is always
 *   present -- the budget controls *which* waypoints are in the
 *   GeoJSON source, not whether the layer exists.
 *
 * Call this inside the `MaplibreMap { ... }` content lambda.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun RouteLayer(routes: List<Route>) {
    val lineGeoJson = remember(routes) {
        GeoJsonData.Features(RouteGeoJson.routeLines(routes))
    }
    val pointGeoJson = remember(routes) {
        GeoJsonData.Features(RouteGeoJson.waypointPoints(routes))
    }

    val lineSource = rememberGeoJsonSource(data = lineGeoJson)
    val pointSource = rememberGeoJsonSource(data = pointGeoJson)

    // Route polyline -- always visible, not budgeted.
    org.maplibre.compose.layers.LineLayer(
        id = "route-line",
        source = lineSource,
        color = const(ROUTE_LINE_COLOR),
        width = const(4.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )

    // Waypoint markers with labels.
    org.maplibre.compose.layers.SymbolLayer(
        id = "route-waypoints",
        source = pointSource,
        textField = format(
            span(feature.get("name") as Expression<StringValue>),
            span("\n"),
            span(feature.get("type") as Expression<StringValue>),
        ),
        textSize = const(12.sp),
        textColor = const(LABEL_TEXT_COLOR),
        textHaloColor = const(LABEL_HALO_COLOR),
        textHaloWidth = const(1.5.dp),
        textAnchor = const(SymbolAnchor.Top),
        textOffset = offset(0.sp, 1.2.sp),
        iconImage = image("marker-15"),
        iconSize = const(1.5f),
        iconColor = const(ROUTE_LINE_COLOR),
        iconAllowOverlap = const(true),
        textAllowOverlap = const(false),
    )
}
