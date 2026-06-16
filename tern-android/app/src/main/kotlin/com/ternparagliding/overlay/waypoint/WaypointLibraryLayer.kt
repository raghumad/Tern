package com.ternparagliding.overlay.waypoint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import com.ternparagliding.model.LibraryWaypoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Library waypoints render in a distinct violet — set apart from PG-spot teal and
 *  the role-coloured task markers. */
internal const val WAYPOINT_VIOLET = 0xFF7C4DFF.toInt()
private const val S = 2.5f // supersample for crisp scaling

/**
 * Renders the standalone **waypoint library** on the map — the same first-class
 * treatment PG spots get: one GeoJSON source, one data-driven SymbolLayer, a
 * tappable marker per waypoint. MapLibre's collision (`iconAllowOverlap=false`)
 * declutters dense comp sets at low zoom. Tapping a marker hands the waypoint's
 * code/lat/lon/alt up so the caller can open the weather/Flyability read.
 */
@Composable
fun WaypointLibraryLayer(
    waypoints: List<LibraryWaypoint>,
    onClick: (code: String, lat: Double, lon: Double, altM: Double?) -> Unit = { _, _, _, _ -> },
) {
    val featureCollection = remember(waypoints) {
        FeatureCollection(
            waypoints.map { wp ->
                Feature<Point, JsonObject>(
                    Point(Position(wp.lon, wp.lat)),
                    JsonObject(
                        mapOf(
                            "code" to JsonPrimitive(wp.code),
                            "alt" to JsonPrimitive(wp.alt ?: Double.NaN),
                        )
                    ),
                )
            }
        )
    }

    val source = rememberGeoJsonSource(data = GeoJsonData.Features(featureCollection))

    val codes = remember(waypoints) { waypoints.map { it.code }.filter { it.isNotBlank() }.distinct() }
    if (codes.isEmpty()) return

    @Suppress("UNCHECKED_CAST")
    val codeExpr = feature.get("code") as Expression<StringValue>
    val iconImage = remember(codes) {
        val transparent = image(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap())
        val cases = codes.map { case(it, image(renderWaypointMarkerBitmap(it).asImageBitmap())) }.toTypedArray()
        switch(codeExpr, *cases, fallback = transparent)
    }

    org.maplibre.compose.layers.SymbolLayer(
        id = "waypoint-library",
        source = source,
        iconImage = iconImage,
        iconAllowOverlap = const(false), // declutter dense comp sets
        onClick = { features ->
            val f = features.firstOrNull()
            val pt = f?.geometry as? Point
            if (pt != null) {
                val code = (f.properties?.get("code") as? JsonPrimitive)?.content ?: "WP"
                val alt = (f.properties?.get("alt") as? JsonPrimitive)?.doubleOrNull?.takeIf { !it.isNaN() }
                onClick(code, pt.coordinates.latitude, pt.coordinates.longitude, alt)
                org.maplibre.compose.util.ClickResult.Consume
            } else org.maplibre.compose.util.ClickResult.Pass
        },
    )
}

/**
 * A violet disc with a white border and the code on a dark pill below. Vertically
 * symmetric (empty top band mirrors the bottom pill) so the CENTER anchor lands
 * the disc on the geo point — same trick as the PG-spot marker.
 */
internal fun renderWaypointMarkerBitmap(code: String): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 10f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WAYPOINT_VIOLET; style = Paint.Style.FILL }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * S
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(190, 20, 20, 20) }

    val disc = 18f * S
    val gap = 3f * S
    val pH = 5f * S
    val pV = 2.5f * S
    val pR = 5f * S

    val textW = textPaint.measureText(code)
    val pillW = textW + pH * 2
    val pillH = (textPaint.descent() - textPaint.ascent()) + pV * 2

    val halfW = maxOf(disc / 2f, pillW / 2f) + gap
    val w = halfW * 2f
    val band = gap + pillH
    val h = band + disc + band

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = h / 2f

    c.drawCircle(cx, cy, disc / 2f, discPaint)
    c.drawCircle(cx, cy, disc / 2f, borderPaint)

    val pillTop = cy + disc / 2f + gap
    c.drawRoundRect(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH, pR, pR, pillPaint)
    c.drawText(code, cx, pillTop + pV - textPaint.ascent(), textPaint)

    return bmp
}
