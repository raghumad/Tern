package com.ternparagliding.overlay.mezulla

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import com.ternparagliding.mezulla.redux.KnownPeer
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
import org.maplibre.compose.util.MaplibreComposable
import org.osmdroid.util.GeoPoint
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

private const val S = 2.5f

/**
 * Below this map zoom, peers render as the compact puck; at/above, the full HUD.
 *
 * Set below [com.ternparagliding.flight.FlightCamera.MIN_ZOOM] (10.5) so the full
 * marker is the default across the ENTIRE in-flight auto-zoom band (10.5–15) and
 * normal hands-on review — the rich left/right/bottom reads are the primary buddy
 * interface, not a zoom-in reward. The compact puck is only for wide overview,
 * where the per-peer declutter ladder ([DeclutterLevel]) has already thinned the
 * full markers anyway. (Was 11.0, which sat at the bottom edge of the flight band,
 * so fast glides / zoomed-out views showed only bare pucks.)
 */
private const val ZOOM_FULL = 9.0

/**
 * Renders each known peer as a full pilot HUD on the map: a staleness-
 * coloured puck (person glyph) with a track arrow on its rim, the buddy's
 * relative altitude and distance from the pilot in side pills, callsign on
 * top, and climb (+ ground speed when uncluttered), or STALE/LOST status, on
 * the bottom. Crowded screens declutter per [DeclutterLevel].
 *
 * Pattern follows the canonical maplibre-compose example: ONE GeoJSON
 * source with all peer features, ONE SymbolLayer whose `iconImage` is
 * data-driven per feature via a `switch` over the `markerImage` property.
 * Each peer's HUD is rasterised by [renderMarkerBitmap] and bound inline as
 * an `image(bitmap)`. Compose layers must NOT be emitted in a forEach loop
 * (MapNodeApplier drops loop-emitted nodes), which is why a single
 * data-driven layer is used instead of one layer per peer.
 *
 * Declutter: a `step` on the map zoom swaps each peer's icon between a
 * compact puck (zoomed out) and the full HUD (zoomed in). Icons always
 * overlap-allow — a buddy marker is never auto-hidden by collision, since
 * losing sight of a peer in flight is a safety regression.
 */
@Composable
@MaplibreComposable
fun PeerLayer(
    peers: Map<Long, KnownPeer>,
    lastEventTime: Instant,
    ownLocation: GeoPoint? = null,
    nerdFont: Typeface? = null,
    labelFont: Typeface? = null,
    altitudeUnit: String = "m",
) {
    // Note: ownLocation is deliberately NOT a remember key. It changes on every own-ship fix
    // (~8/s in replay), and re-keying here would rebuild every peer bitmap that often and starve
    // the UI thread. The bundle (and its relative alt/distance) instead refreshes on peer events
    // (throttled upstream) using whatever ownLocation is current then — at most a few hundred ms stale.
    val bundle = remember(peers, lastEventTime, altitudeUnit) {
        buildPeerBundle(peers, lastEventTime, ownLocation, altitudeUnit)
    }

    if (bundle.specs.isEmpty()) return

    val source = rememberGeoJsonSource(
        data = GeoJsonData.JsonString(bundle.geoJson),
    )

    @Suppress("UNCHECKED_CAST")
    val markerImage = feature.get("markerImage") as Expression<StringValue>

    val iconImage = remember(bundle, nerdFont, labelFont) {
        val transparent = image(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        )
        val fullCases = bundle.specs.map { spec ->
            case(spec.imageName, image(renderMarkerBitmap(spec, nerdFont, labelFont).asImageBitmap()))
        }.toTypedArray()
        val compactCases = bundle.specs.map { spec ->
            case(spec.imageName, image(renderCompactBitmap(spec, nerdFont, labelFont).asImageBitmap()))
        }.toTypedArray()
        step(
            zoom(),
            switch(markerImage, *compactCases, fallback = transparent),
            ZOOM_FULL to switch(markerImage, *fullCases, fallback = transparent),
        )
    }

    org.maplibre.compose.layers.SymbolLayer(
        id = "mezulla-peers",
        source = source,
        iconImage = iconImage,
        iconSize = const(0.75f), // smaller markers — the deck instruments crowd the screen
        iconAllowOverlap = const(true),
    )
}

private val savedDebugBitmap = java.util.concurrent.atomic.AtomicBoolean(false)

/** White triangle on the puck rim pointing along [trackDeg] (0 = up/N). */
private fun drawTrackArrow(c: Canvas, cx: Float, cy: Float, rimR: Float, trackDeg: Float) {
    val a = Math.toRadians(trackDeg.toDouble())
    val tip = (rimR + 13f * S)
    val base = (rimR + 1f * S)
    val spread = 0.42
    val path = Path().apply {
        moveTo(cx + tip * sin(a).toFloat(), cy - tip * cos(a).toFloat())
        lineTo(cx + base * sin(a - spread).toFloat(), cy - base * cos(a - spread).toFloat())
        lineTo(cx + base * sin(a + spread).toFloat(), cy - base * cos(a + spread).toFloat())
        close()
    }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.FILL
    })
}

/**
 * Full peer HUD bitmap. The puck centre sits at the bitmap's centre (top
 * callsign band and bottom status band are equal height), so the map symbol
 * anchors the geo point on the puck with the default CENTER anchor.
 */
internal fun renderMarkerBitmap(spec: MarkerSpec, nerdFont: Typeface?, labelFont: Typeface? = null): Bitmap {
    android.util.Log.i("PeerLayer",
        "render: callsign='${spec.callsign}' track=${spec.trackDegrees} " +
        "dalt='${spec.deltaAltText}' dist='${spec.distanceText}' bottom='${spec.bottomText}'")

    // Match the off-screen indicator chips: Gruppo when available, bold-default otherwise.
    val textFace = labelFont ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    val callsignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 13f * S
        typeface = textFace
        textAlign = Paint.Align.CENTER
    }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 12f * S
        typeface = textFace
    }
    val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 15f * S; typeface = nerdFont
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(190, 20, 20, 20); style = Paint.Style.FILL
    }
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.puckColor; style = Paint.Style.FILL
    }
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(90, 255, 255, 255); style = Paint.Style.STROKE
        strokeWidth = 2.5f * S
    }
    val btmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.bottomColor; textSize = 10f * S
        typeface = textFace
        textAlign = Paint.Align.CENTER
    }

    val circleR = 14f * S
    val gap = 4f * S
    val pH = 7f * S   // pill horizontal padding
    val pV = 3f * S   // pill vertical padding
    val pR = 6f * S   // pill corner radius

    val csH = callsignPaint.descent() - callsignPaint.ascent()
    val bandH = csH + pV * 2                      // callsign / status band height
    val csW = callsignPaint.measureText(spec.callsign)
    val csPillW = csW + pH * 2

    val daltW = if (spec.deltaAltText.isNotEmpty()) valuePaint.measureText(spec.deltaAltText) + pH * 2 else 0f
    val distW = if (spec.distanceText.isNotEmpty()) valuePaint.measureText(spec.distanceText) + pH * 2 else 0f
    val valH = valuePaint.descent() - valuePaint.ascent()
    val sidePillH = valH + pV * 2

    val btmW = if (spec.bottomText.isNotEmpty()) btmPaint.measureText(spec.bottomText) + pH * 2 else 0f

    val leftExtent = circleR + (if (daltW > 0) gap + daltW else 0f)
    val rightExtent = circleR + (if (distW > 0) gap + distW else 0f)
    val halfW = maxOf(leftExtent, rightExtent, csPillW / 2f, btmW / 2f) + gap
    val w = halfW * 2
    // Symmetric vertical layout → puck at centre.
    val h = bandH + gap + circleR * 2 + gap + bandH

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = h / 2f

    // Callsign pill (top band)
    c.drawRoundRect(cx - csPillW / 2f, cy - circleR - gap - bandH, cx - csPillW / 2f + csPillW,
        cy - circleR - gap, pR, pR, pillPaint)
    c.drawText(spec.callsign, cx, cy - circleR - gap - bandH + pV - callsignPaint.ascent(), callsignPaint)

    // Track arrow (drawn behind the puck so the rim overlaps its base)
    spec.trackDegrees?.let { drawTrackArrow(c, cx, cy, circleR, it) }

    // Puck
    c.drawCircle(cx, cy, circleR, circlePaint)
    c.drawCircle(cx, cy, circleR, ringPaint)
    val gb = android.graphics.Rect()
    glyphPaint.getTextBounds(spec.glyph, 0, spec.glyph.length, gb)
    c.drawText(spec.glyph, cx - (gb.left + gb.right) / 2f, cy - (gb.top + gb.bottom) / 2f, glyphPaint)

    // Left pill — relative altitude
    if (spec.deltaAltText.isNotEmpty()) {
        valuePaint.color = spec.deltaAltColor
        val pr = cx - circleR - gap
        val pl = pr - daltW
        c.drawRoundRect(pl, cy - sidePillH / 2f, pr, cy + sidePillH / 2f, pR, pR, pillPaint)
        c.drawText(spec.deltaAltText, pl + pH, cy - (valuePaint.descent() + valuePaint.ascent()) / 2f, valuePaint)
    }

    // Right pill — distance
    if (spec.distanceText.isNotEmpty()) {
        valuePaint.color = AndroidColor.WHITE
        val pl = cx + circleR + gap
        c.drawRoundRect(pl, cy - sidePillH / 2f, pl + distW, cy + sidePillH / 2f, pR, pR, pillPaint)
        c.drawText(spec.distanceText, pl + pH, cy - (valuePaint.descent() + valuePaint.ascent()) / 2f, valuePaint)
    }

    // Bottom pill — view-mode metric or status
    if (spec.bottomText.isNotEmpty()) {
        val top = cy + circleR + gap
        c.drawRoundRect(cx - btmW / 2f, top, cx + btmW / 2f, top + bandH, pR, pR, pillPaint)
        c.drawText(spec.bottomText, cx, top + pV - btmPaint.ascent(), btmPaint)
    }

    if (savedDebugBitmap.compareAndSet(false, true)) {
        android.util.Log.i("PeerLayer", "render: first full bitmap ${bmp.width}x${bmp.height}")
    }
    return bmp
}

/**
 * Compact peer marker for the decluttered (zoomed-out) state: just the
 * staleness-coloured puck with a track arrow and a short tag below. Puck is
 * vertically centred (matching [renderMarkerBitmap]) so the anchor is stable
 * across the zoom swap.
 */
internal fun renderCompactBitmap(spec: MarkerSpec, nerdFont: Typeface?, labelFont: Typeface? = null): Bitmap {
    val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 9f * S
        typeface = labelFont ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 9f * S; typeface = nerdFont
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(190, 20, 20, 20); style = Paint.Style.FILL
    }
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.puckColor; style = Paint.Style.FILL
    }
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(90, 255, 255, 255); style = Paint.Style.STROKE
        strokeWidth = 2f * S
    }

    val circleR = 9f * S
    val gap = 3f * S
    val pH = 5f * S
    val pV = 2f * S
    val pR = 4f * S
    val tagH = (tagPaint.descent() - tagPaint.ascent()) + pV * 2
    val tagW = tagPaint.measureText(spec.shortTag) + pH * 2

    val arrowReach = circleR + 13f * S
    val halfW = maxOf(arrowReach, tagW / 2f) + gap
    val w = halfW * 2
    val h = tagH + gap + circleR * 2 + gap + tagH  // symmetric → puck centred

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = h / 2f

    spec.trackDegrees?.let { drawTrackArrow(c, cx, cy, circleR, it) }
    c.drawCircle(cx, cy, circleR, circlePaint)
    c.drawCircle(cx, cy, circleR, ringPaint)
    val gb = android.graphics.Rect()
    glyphPaint.getTextBounds(spec.glyph, 0, spec.glyph.length, gb)
    c.drawText(spec.glyph, cx - (gb.left + gb.right) / 2f, cy - (gb.top + gb.bottom) / 2f, glyphPaint)

    val top = cy + circleR + gap
    c.drawRoundRect(cx - tagW / 2f, top, cx + tagW / 2f, top + tagH, pR, pR, pillPaint)
    c.drawText(spec.shortTag, cx, top + pV - tagPaint.ascent(), tagPaint)

    return bmp
}
