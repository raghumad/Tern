package com.ternparagliding.overlay.pgspot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.platform.LocalContext
import com.ternparagliding.R
import com.ternparagliding.weather.SiteContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** Tern-icon teal (Material Cyan) — the marker's badge background / fallback. */
internal const val PG_SPOT_TEAL = 0xFF00BCD4.toInt()

/** Bitmap supersampling factor — keeps the marker crisp when scaled by zoom. */
private const val S = 2.5f

/**
 * MapLibre Compose layer that renders PG spots as **icon bitmaps**: the Tern
 * bird app icon as a rounded-square badge over a name pill.
 *
 * Why a bitmap and not `textField`: glyph text on a SymbolLayer only renders
 * if the active map style ships a `glyphs` source AND a matching `textFont`.
 * Tern's raster styles don't, so the original text layer drew nothing. Peers
 * and hazards already avoid this by rasterising their own bitmaps; PG spots
 * now do too, so the marker is legible on every style.
 *
 * Pattern mirrors `PeerLayer`: ONE GeoJSON source, ONE SymbolLayer whose
 * `iconImage` is data-driven via a `switch` over the feature `name`
 * property. MapLibre handles collision (`iconAllowOverlap=false`), so dense
 * clusters auto-declutter.
 */
@Composable
fun PgSpotLayer(
    featureCollection: FeatureCollection<Geometry, JsonObject>,
    // Tapping a site is the weekend-pilot entry point to "is it flyable here?":
    // the handler gets the spot's name, coordinates, and launch geometry (elevation +
    // orientation, as a SiteContext) so the caller can fetch weather and open a
    // *site-aware* Flyability read. Default no-op keeps previews/tests simple.
    onSpotClick: (name: String, lat: Double, lng: Double, site: SiteContext) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val birdIcon = remember {
        runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.tern_pgspot) }
            .getOrNull()
    }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(featureCollection),
    )

    val names = remember(featureCollection) {
        featureCollection.features
            .mapNotNull { (it.properties?.get("name") as? JsonPrimitive)?.content }
            .filter { it.isNotBlank() }
            .distinct()
    }

    if (names.isEmpty()) return

    @Suppress("UNCHECKED_CAST")
    val nameExpr = feature.get("name") as Expression<StringValue>

    val iconImage = remember(names, birdIcon) {
        val transparent = image(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        )
        val cases = names.map { name ->
            case(name, image(renderPgSpotBitmap(name, birdIcon).asImageBitmap()))
        }.toTypedArray()
        switch(nameExpr, *cases, fallback = transparent)
    }

    // RFC 005 scaling: smaller at regional zoom, full size when zoomed in.
    val iconSize = step(
        zoom(),
        const(0.4f),
        8.0 to const(0.6f),
        12.0 to const(0.75f),
    )

    org.maplibre.compose.layers.SymbolLayer(
        id = "pg-spot-symbols",
        source = source,
        iconImage = iconImage,
        iconSize = iconSize,
        iconAllowOverlap = const(false),
        onClick = { features ->
            // The clicked feature carries the Point geometry + "name" property that
            // overlayFeaturesToGeoJson wrote. Pull both and hand them up; consume the
            // tap so it doesn't fall through to layers below (airspace, etc.).
            val f = features.firstOrNull()
            val pt = f?.geometry as? org.maplibre.spatialk.geojson.Point
            if (pt != null) {
                val name = (f.properties?.get("name") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "Site"
                onSpotClick(name, pt.coordinates.latitude, pt.coordinates.longitude, siteContextFromJsonProps(f.properties))
                org.maplibre.compose.util.ClickResult.Consume
            } else {
                org.maplibre.compose.util.ClickResult.Pass
            }
        },
    )
}

/**
 * Rasterises a PG-spot marker: the Tern bird icon as a rounded-square badge
 * with a white border, and the site name on a dark pill below. The bitmap is
 * vertically symmetric (empty top band mirrors the bottom pill) so the
 * default CENTER symbol anchor lands the badge on the geo point. Falls back
 * to a plain teal badge if the icon can't be decoded. Exposed for the render
 * test.
 */
internal fun renderPgSpotBitmap(name: String, birdIcon: Bitmap?): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 11f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(190, 20, 20, 20); style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * S
    }
    val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PG_SPOT_TEAL; style = Paint.Style.FILL
    }

    val badge = 32f * S        // badge square side
    val corner = 8f * S
    val gap = 3f * S
    val pH = 6f * S
    val pV = 3f * S
    val pR = 5f * S

    val textW = textPaint.measureText(name)
    val pillW = textW + pH * 2
    val pillH = (textPaint.descent() - textPaint.ascent()) + pV * 2

    val halfW = maxOf(badge / 2f, pillW / 2f) + gap
    val w = halfW * 2f
    // Symmetric: (gap+pillH) top band | badge | (gap+pillH) bottom band → badge centred.
    val band = gap + pillH
    val h = band + badge + band

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = h / 2f

    val left = cx - badge / 2f
    val top = cy - badge / 2f
    val badgeRect = RectF(left, top, left + badge, top + badge)

    // Rounded-square badge: clip, draw icon (or teal fallback), then white border.
    c.save()
    val clip = Path().apply { addRoundRect(badgeRect, corner, corner, Path.Direction.CW) }
    c.clipPath(clip)
    if (birdIcon != null) {
        c.drawBitmap(birdIcon, Rect(0, 0, birdIcon.width, birdIcon.height), badgeRect, Paint(Paint.FILTER_BITMAP_FLAG))
    } else {
        c.drawRect(badgeRect, fallbackPaint)
    }
    c.restore()
    c.drawRoundRect(badgeRect, corner, corner, borderPaint)

    // Name pill below the badge.
    val pillTop = cy + badge / 2f + gap
    c.drawRoundRect(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH, pR, pR, pillPaint)
    c.drawText(name, cx, pillTop + pV - textPaint.ascent(), textPaint)

    return bmp
}
