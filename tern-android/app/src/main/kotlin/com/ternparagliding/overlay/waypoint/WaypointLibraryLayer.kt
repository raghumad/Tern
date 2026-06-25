package com.ternparagliding.overlay.waypoint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
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

/** Nerd Font glyph for the waypoint badge — a flag (nf-fa-flag). */
private const val FLAG_GLYPH = ""
private const val ELEV_GLYPH_CP = 0xF0509 // elevation icon (md-terrain, twin peaks)

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
    nerdFont: Typeface? = null,
    onClick: (code: String, lat: Double, lon: Double, altM: Double?) -> Unit = { _, _, _, _ -> },
) {
    val featureCollection = remember(waypoints) {
        FeatureCollection(
            waypoints.map { wp ->
                val props = buildMap {
                    put("code", JsonPrimitive(wp.code))
                    wp.alt?.let { put("alt", JsonPrimitive(it)) } // omit when absent
                }
                Feature<Point, JsonObject>(
                    Point(Position(wp.lon, wp.lat)),
                    JsonObject(props),
                )
            }
        )
    }

    val source = rememberGeoJsonSource(data = GeoJsonData.Features(com.ternparagliding.utils.geo.GeoJsonSafe.sanitize(featureCollection)))

    // One marker per distinct code; carry each code's elevation for the static pill.
    val byCode = remember(waypoints) {
        waypoints.filter { it.code.isNotBlank() }.associateBy { it.code }.values.toList()
    }
    if (byCode.isEmpty()) return

    @Suppress("UNCHECKED_CAST")
    val codeExpr = feature.get("code") as Expression<StringValue>
    val iconImage = remember(byCode, nerdFont) {
        val transparent = image(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap())
        val cases = byCode.map { wp ->
            val elev = wp.alt?.let { "${it.toInt()} m" }
            case(wp.code, image(renderWaypointMarkerBitmap(wp.code, wp.name, elev, nerdFont).asImageBitmap()))
        }.toTypedArray()
        switch(codeExpr, *cases, fallback = transparent)
    }

    // Same zoom-scaling as PG spots so the two marker families read at one size.
    val iconSize = step(zoom(), const(0.4f), 8.0 to const(0.6f), 12.0 to const(0.75f))

    org.maplibre.compose.layers.SymbolLayer(
        id = "waypoint-library",
        source = source,
        iconImage = iconImage,
        iconSize = iconSize,
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
 * A violet rounded-square **badge** (same geometry as the PG-spot marker) with a white
 * flag glyph, then dark pills below: the (cryptic) **code**, the human **description**
 * when set (codes like "BUTTE" are cryptic — the description "Chelan Butte" reads at a
 * glance), and — buddy-style — a static **elevation**. Vertically symmetric (empty top
 * band mirrors the bottom pills) so the CENTER anchor lands the badge on the geo point.
 */
internal fun renderWaypointMarkerBitmap(code: String, name: String?, elevText: String?, nerdFont: Typeface?): Bitmap {
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 11f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    // Elevation (right-side pill): mountain glyph + value, both subdued.
    val elevGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt(); textSize = 11f * S
        typeface = nerdFont ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT
    }
    val elevValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt(); textSize = 10f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT
    }
    val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 17f * S
        typeface = nerdFont ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WAYPOINT_VIOLET; style = Paint.Style.FILL }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * S
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(190, 20, 20, 20) }

    val badge = 32f * S
    val corner = 8f * S
    val gap = 3f * S
    val pH = 6f * S
    val pV = 2.5f * S
    val pR = 5f * S
    val innerGap = 4f * S

    val flagGlyph = if (nerdFont != null) FLAG_GLYPH else "WP"

    // Label: "id - desc" when a description is set, else just the code.
    val showName = !name.isNullOrBlank() && name != code
    val label = if (showName) "$code - $name" else code
    val labelW = labelPaint.measureText(label) + pH * 2
    val labelH = (labelPaint.descent() - labelPaint.ascent()) + pV * 2

    // Elevation pill (right of the badge): [⛰ 849m].
    val hasElev = elevText != null
    val elevGlyph = if (hasElev && nerdFont != null) String(Character.toChars(ELEV_GLYPH_CP)) else ""
    val egW = if (hasElev && nerdFont != null) elevGlyphPaint.measureText(elevGlyph) + innerGap else 0f
    val evW = if (hasElev) elevValPaint.measureText(elevText) else 0f
    val elevPillW = if (hasElev) pH + egW + evW + pH else 0f
    val elevPillH = if (hasElev) (elevValPaint.descent() - elevValPaint.ascent()) + pV * 2 else 0f

    // Horizontal: badge centred → pad the left to match the right (elev pill extends right).
    val rightExtent = badge / 2f + (if (hasElev) gap + elevPillW else 0f)
    val halfW = maxOf(rightExtent, badge / 2f, labelW / 2f) + gap
    val w = halfW * 2f
    // Vertical: label band below; equal empty band on top → badge centred on the geo point.
    val labelBand = gap + labelH
    val h = labelBand + badge + labelBand

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = h / 2f

    // Badge + flag glyph
    val badgeRect = RectF(cx - badge / 2f, cy - badge / 2f, cx + badge / 2f, cy + badge / 2f)
    c.drawRoundRect(badgeRect, corner, corner, discPaint)
    c.drawRoundRect(badgeRect, corner, corner, borderPaint)
    val gb = Rect()
    glyphPaint.getTextBounds(flagGlyph, 0, flagGlyph.length, gb)
    c.drawText(flagGlyph, cx, cy - (gb.top + gb.bottom) / 2f, glyphPaint)

    // Elevation pill — right of the badge, vertically centred on it.
    if (hasElev) {
        val pl = cx + badge / 2f + gap
        val pt = cy - elevPillH / 2f
        c.drawRoundRect(pl, pt, pl + elevPillW, pt + elevPillH, pR, pR, pillPaint)
        var x = pl + pH
        if (nerdFont != null) {
            c.drawText(elevGlyph, x, cy - (elevGlyphPaint.descent() + elevGlyphPaint.ascent()) / 2f, elevGlyphPaint)
            x += egW
        }
        c.drawText(elevText, x, cy - (elevValPaint.descent() + elevValPaint.ascent()) / 2f, elevValPaint)
    }

    // Label pill — below the badge.
    val labelTop = cy + badge / 2f + gap
    c.drawRoundRect(cx - labelW / 2f, labelTop, cx + labelW / 2f, labelTop + labelH, pR, pR, pillPaint)
    c.drawText(label, cx, labelTop + pV - labelPaint.ascent(), labelPaint)

    return bmp
}
