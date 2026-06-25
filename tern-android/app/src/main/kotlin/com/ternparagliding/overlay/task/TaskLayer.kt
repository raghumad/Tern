package com.ternparagliding.overlay.task

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Task
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

// Tern cockpit palette -- task line is neon cyan over a dark casing.
private val TASK_LINE_COLOR = Color(0xFF00E5FF)
private val TASK_CASING_COLOR = Color(0xCC0A1417)

/** Above this zoom, waypoint markers show the detailed tier (name + cylinder + elevation + gate). */
private const val ZOOM_DETAIL = 10.0

/** One rasterised marker per waypoint, keyed for the data-driven SymbolLayer. */
private data class WpSpec(
    val key: String,
    val wpId: String,
    val type: LocationType,
    val seq: Int,
    val name: String,
    val radiusM: Double,
    val altM: Double?,
    val openTime: String?,
    val closeTime: String?,
)

/**
 * Renders tasks on a MapLibre map, cylinder-centric:
 *  - **FAI cylinder** fill + ring per waypoint (role-coloured) — the
 *    waypoint's identity on the ground.
 *  - **Task line** (neon cyan) over a dark casing.
 *  - **Leg-distance pills** on the line at each leg midpoint ("25 km").
 *  - **Waypoint markers** — small role-coloured centre + short code, growing
 *    to name + radius when zoomed in (icon bitmaps, since glyph `textField`
 *    doesn't render on Tern's raster styles).
 *
 * Call inside the `MaplibreMap { ... }` content lambda.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun TaskLayer(
    tasks: List<Task>,
    selectedWaypointId: String? = null,
    activeWaypointId: String? = null,
    nerdFont: android.graphics.Typeface? = null,
    onWaypointClick: ((taskId: String, waypointId: String) -> Unit)? = null,
) {
    val visible = tasks.filter { it.isVisible }

    val lineSource = rememberGeoJsonSource(
        data = remember(tasks) { GeoJsonData.Features(com.ternparagliding.utils.geo.GeoJsonSafe.sanitize(TaskGeoJson.taskLines(tasks))) },
    )
    val cylinderSource = rememberGeoJsonSource(
        data = remember(tasks) { GeoJsonData.Features(com.ternparagliding.utils.geo.GeoJsonSafe.sanitize(TaskGeoJson.taskCylinders(tasks))) },
    )
    val legSource = rememberGeoJsonSource(
        data = remember(tasks) { GeoJsonData.Features(com.ternparagliding.utils.geo.GeoJsonSafe.sanitize(TaskGeoJson.legMidpoints(tasks))) },
    )
    val pointSource = rememberGeoJsonSource(
        data = remember(tasks) { GeoJsonData.Features(com.ternparagliding.utils.geo.GeoJsonSafe.sanitize(TaskGeoJson.waypointPoints(tasks))) },
    )

    val typeExpr = feature.get("type") as Expression<StringValue>
    val cylinderFill = remember { cylinderColorExpr(typeExpr, 0.18f) }
    val cylinderRing = remember { cylinderColorExpr(typeExpr, 1.0f) }

    // ── FAI cylinders (drawn first, beneath everything) ──────────────────
    // The ring IS the waypoint's identity on the ground, so make it read clearly:
    // a brighter, thicker outline over a slightly stronger fill.
    org.maplibre.compose.layers.FillLayer(
        id = "task-cylinder-fill",
        source = cylinderSource,
        color = cylinderFill,
    )
    org.maplibre.compose.layers.LineLayer(
        id = "task-cylinder-ring",
        source = cylinderSource,
        color = cylinderRing,
        width = const(3.dp),
    )

    // ── Task line: dark casing then neon line ───────────────────────────
    org.maplibre.compose.layers.LineLayer(
        id = "task-line-casing", source = lineSource,
        color = const(TASK_CASING_COLOR), width = const(4.5.dp),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
    org.maplibre.compose.layers.LineLayer(
        id = "task-line", source = lineSource,
        color = const(TASK_LINE_COLOR), width = const(2.5.dp),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )

    // ── Leg-distance pills on the line ───────────────────────────────────
    val legLabels = remember(tasks) {
        TaskGeoJson.legMidpoints(tasks).features
            .mapNotNull { (it.properties?.get("label") as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .filter { it.isNotBlank() }.distinct()
    }
    if (legLabels.isNotEmpty()) {
        val labelExpr = feature.get("label") as Expression<StringValue>
        val legIcon = remember(legLabels) {
            val transparent = image(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap())
            val cases = legLabels.map { case(it, image(renderLegPillBitmap(it).asImageBitmap())) }.toTypedArray()
            switch(labelExpr, *cases, fallback = transparent)
        }
        org.maplibre.compose.layers.SymbolLayer(
            id = "task-leg-pills", source = legSource,
            iconImage = legIcon, iconAllowOverlap = const(false),
        )
    }

    // ── Waypoint markers (zoom-adaptive, cylinder-centric) ───────────────
    val specs = remember(tasks) {
        visible.flatMap { task ->
            var tp = 0
            task.waypoints.mapIndexed { i, wp ->
                val seq = if (wp.type == LocationType.TURNPOINT) ++tp else i + 1
                WpSpec("${task.id}:${wp.id}", wp.id, wp.type, seq, wp.displayName ?: "WP ${i + 1}", wp.radius ?: 0.0, wp.alt, wp.openTime, wp.closeTime)
            }
        }
    }
    if (specs.isNotEmpty()) {
        val markerKeyExpr = feature.get("markerKey") as Expression<StringValue>
        val iconImage = remember(specs, selectedWaypointId, activeWaypointId, nerdFont) {
            val transparent = image(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap())
            // The active (next) waypoint and the editing-selected waypoint both
            // get the enlarged + haloed treatment so they stand out.
            fun highlit(s: WpSpec) = s.key == selectedWaypointId || s.wpId == activeWaypointId
            val compact = specs.map { s ->
                case(s.key, image(renderWaypointBitmap(s.type, s.seq, s.name, selected = highlit(s), detailed = false, nerdFont = nerdFont).asImageBitmap()))
            }.toTypedArray()
            val detailed = specs.map { s ->
                case(s.key, image(renderWaypointBitmap(s.type, s.seq, s.name, selected = highlit(s), detailed = true, radiusM = s.radiusM, altM = s.altM, openTime = s.openTime, closeTime = s.closeTime, nerdFont = nerdFont).asImageBitmap()))
            }.toTypedArray()
            step(
                zoom(),
                switch(markerKeyExpr, *compact, fallback = transparent),
                ZOOM_DETAIL to switch(markerKeyExpr, *detailed, fallback = transparent),
            )
        }
        org.maplibre.compose.layers.SymbolLayer(
            id = "task-waypoints",
            source = pointSource,
            iconImage = iconImage,
            iconSize = const(1f),
            iconAllowOverlap = const(true), // waypoints are navigation — never hide
            onClick = onWaypointClick?.let { cb ->
                { features ->
                    // The tapped feature carries the taskId + waypointId TaskGeoJson wrote.
                    // Hand both up (→ SelectWaypoint) and consume so the tap doesn't fall
                    // through to layers below (PG spots, airspace).
                    val props = features.firstOrNull()?.properties
                    val taskId = (props?.get("taskId") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val wpId = (props?.get("waypointId") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (taskId != null && wpId != null) {
                        cb(taskId, wpId)
                        org.maplibre.compose.util.ClickResult.Consume
                    } else {
                        org.maplibre.compose.util.ClickResult.Pass
                    }
                }
            },
        )
    }
}

/** switch(type → role colour at [alpha]) for the cylinder fill/ring. */
private fun cylinderColorExpr(
    typeExpr: Expression<StringValue>,
    alpha: Float,
): Expression<ColorValue> {
    fun c(type: LocationType) = const(Color(cylinderColor(type)).copy(alpha = alpha))
    return switch(
        typeExpr,
        case("LAUNCH", c(LocationType.LAUNCH)),
        case("SSS", c(LocationType.SSS)),
        case("ESS", c(LocationType.ESS)),
        case("GOAL", c(LocationType.GOAL)),
        case("LANDING", c(LocationType.LANDING)),
        fallback = c(LocationType.TURNPOINT),
    )
}
