package com.ternparagliding.overlay.hazard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.serialization.json.JsonObject
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

/** Exact colours asserted by ResourceAuditTest and used by WindGaugeMarker. */
internal const val AMBER_HAZARD = 0xFFFFBF00.toInt()
internal const val RED_HAZARD = 0xFFE53935.toInt()

/** Bitmap supersampling factor — keeps the halo crisp when scaled by zoom. */
private const val S = 2.5f

/**
 * MapLibre Compose layer that draws a hazard halo at each hazardous site:
 * an amber ring for convective danger, a red ring + lightning-bolt badge
 * for thunderstorms. Safety-critical, so icons always overlap-allow (a
 * hazard is never auto-hidden by label collision).
 *
 * Pattern mirrors `PeerLayer`: ONE GeoJSON source, ONE SymbolLayer whose
 * `iconImage` is data-driven via a `switch` over the feature `level`
 * property. Only two distinct bitmaps exist, so they're rasterised once.
 *
 * Zoom declutter (RFC 005 concept): `iconSize` steps down at low zoom so
 * halos shrink to pin-pricks at regional scale instead of swamping the map.
 */
@Composable
fun HazardLayer(
    featureCollection: FeatureCollection<Geometry, JsonObject>,
) {
    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(featureCollection),
    )

    @Suppress("UNCHECKED_CAST")
    val levelExpr = feature.get("level") as Expression<StringValue>

    val iconImage = remember {
        val transparent = image(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        )
        switch(
            levelExpr,
            case(HazardLevel.CONVECTIVE.key, image(renderHazardBitmap(HazardLevel.CONVECTIVE).asImageBitmap())),
            case(HazardLevel.THUNDERSTORM.key, image(renderHazardBitmap(HazardLevel.THUNDERSTORM).asImageBitmap())),
            fallback = transparent,
        )
    }

    // RFC 005 3-stage scaling: pin-prick < z6, reduced z6–10, full > z10.
    val iconSize = step(
        zoom(),
        const(0.35f),
        6.0 to const(0.7f),
        10.0 to const(1.0f),
    )

    org.maplibre.compose.layers.SymbolLayer(
        id = "weather-hazards",
        source = source,
        iconImage = iconImage,
        iconSize = iconSize,
        iconAllowOverlap = const(true),
    )
}

/**
 * Rasterises a hazard halo. Amber ring for convective, red ring + a
 * lightning-bolt badge for thunderstorm. The ring stroke uses the exact
 * [AMBER_HAZARD] / [RED_HAZARD] colours at full opacity so pixel-signature
 * tests have an unambiguous target. Exposed `internal` for the render test.
 */
internal fun renderHazardBitmap(level: HazardLevel): Bitmap {
    val storm = level == HazardLevel.THUNDERSTORM
    val ringColor = if (storm) RED_HAZARD else AMBER_HAZARD

    val ringR = 16f * S
    val glowR = 24f * S
    val pad = glowR + 6f * S
    val size = (pad * 2f).toInt().coerceAtLeast(1)

    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    // Soft glow: a few translucent rings fading outward.
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    for (i in 4 downTo 1) {
        glowPaint.color = (ringColor and 0x00FFFFFF) or ((14 * i) shl 24)
        glowPaint.strokeWidth = (3f + i * 2.5f) * S
        c.drawCircle(cx, cy, ringR + i * 1.5f * S, glowPaint)
    }

    // Solid ring — the unambiguous colour signature.
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * S
    }
    c.drawCircle(cx, cy, ringR, ringPaint)

    // Centre dot so a hazard reads as a marker even with no site under it.
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.FILL
    }
    c.drawCircle(cx, cy, 4f * S, dotPaint)

    if (storm) drawLightningBadge(c, cx + ringR * 0.85f, cy - ringR * 0.85f, 9f * S)

    return bmp
}

/** White circular badge with a red lightning bolt — the storm marker. */
private fun drawLightningBadge(c: Canvas, bx: Float, by: Float, r: Float) {
    c.drawCircle(bx, by, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.FILL
    })
    c.drawCircle(bx, by, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RED_HAZARD; style = Paint.Style.STROKE; strokeWidth = 1.5f * S
    })
    // Classic zigzag bolt, normalised to the badge radius.
    val bolt = Path().apply {
        moveTo(bx + 0.15f * r, by - 0.62f * r)
        lineTo(bx - 0.38f * r, by + 0.10f * r)
        lineTo(bx - 0.02f * r, by + 0.10f * r)
        lineTo(bx - 0.15f * r, by + 0.62f * r)
        lineTo(bx + 0.40f * r, by - 0.14f * r)
        lineTo(bx + 0.04f * r, by - 0.14f * r)
        close()
    }
    c.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RED_HAZARD; style = Paint.Style.FILL
    })
}
