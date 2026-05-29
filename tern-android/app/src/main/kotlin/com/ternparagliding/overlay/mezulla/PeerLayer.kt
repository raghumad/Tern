package com.ternparagliding.overlay.mezulla

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.redux.MezullaViewMode
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import java.time.Instant

private const val S = 2.5f

/**
 * Renders each known peer as a green CircleLayer dot on the map.
 *
 * Pattern follows the canonical maplibre-compose example: ONE GeoJSON
 * source containing all peer features, ONE layer that references it.
 * Compose layers are NOT designed to be instantiated inside a forEach
 * loop — the MapNodeApplier silently drops loop-emitted nodes, which
 * is why the previous per-peer-source pattern produced zero visible
 * markers despite the bitmap render path firing cleanly. See:
 * https://github.com/maplibre/maplibre-compose/blob/main/demo-app/src/commonMain/kotlin/org/maplibre/compose/demoapp/demos/MarkersDemo.kt
 *
 * Current scope: one colour for all peers. Future work (separate
 * commits): staleness-driven colour via a data-driven `case()`
 * expression on the `staleness` feature property; callsign/distance
 * labels via a sibling SymbolLayer with `textField` driven by feature
 * properties.
 */
@Composable
@MaplibreComposable
fun PeerLayer(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    lastEventTime: Instant,
    nerdFont: Typeface? = null,
) {
    val bundle = remember(peers, viewMode, lastEventTime) {
        buildPeerBundle(peers, viewMode, lastEventTime)
    }

    if (bundle.specs.isEmpty()) return

    val source = rememberGeoJsonSource(
        data = GeoJsonData.JsonString(bundle.geoJson),
    )

    org.maplibre.compose.layers.CircleLayer(
        id = "mezulla-peers-circle",
        source = source,
        color = const(ComposeColor(0xFF4CAF50)),
        radius = const(12.dp),
        strokeColor = const(ComposeColor.White),
        strokeWidth = const(2.dp),
    )
}

private val savedDebugBitmap = java.util.concurrent.atomic.AtomicBoolean(false)

internal fun renderMarkerBitmap(spec: MarkerSpec, nerdFont: Typeface): Bitmap {
    android.util.Log.i("PeerLayer",
        "render: callsign='${spec.callsign}' glyph='${spec.glyph}' " +
        "left='${spec.leftValue}${spec.leftUnit}' right='${spec.rightValue}${spec.rightUnit}' " +
        "bottom='${spec.bottomText}' staleness=${spec.staleness}")
    val callsignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 13f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 11f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(160, 255, 255, 255)
        textSize = 8f * S
    }
    val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 15f * S
        typeface = nerdFont
    }
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.glyphColor
        style = Paint.Style.FILL
    }
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * S
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(190, 20, 20, 20)
        style = Paint.Style.FILL
    }

    val circleR = 14f * S
    val gap = 4f * S
    val pH = 6f * S
    val pV = 3f * S
    val pR = 5f * S

    val csH = callsignPaint.descent() - callsignPaint.ascent()
    val csPillH = csH + pV * 2
    val csW = callsignPaint.measureText(spec.callsign)
    val csPillW = csW + pH * 2

    val leftVW = valuePaint.measureText(spec.leftValue)
    val leftUW = unitPaint.measureText(spec.leftUnit)
    val leftPW = if (spec.leftValue.isNotEmpty()) leftVW + leftUW + pH * 2 else 0f
    val rightVW = valuePaint.measureText(spec.rightValue)
    val rightUW = unitPaint.measureText(spec.rightUnit)
    val rightPW = if (spec.rightValue.isNotEmpty()) rightVW + rightUW + pH * 2 else 0f
    val valH = valuePaint.descent() - valuePaint.ascent()
    val pillH = valH + pV * 2

    val btmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.bottomColor
        textSize = 10f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val btmW = if (spec.bottomText.isNotEmpty()) btmPaint.measureText(spec.bottomText) + pH * 2 else 0f

    val leftExtent = circleR + (if (leftPW > 0) gap + leftPW else 0f)
    val rightExtent = circleR + (if (rightPW > 0) gap + rightPW else 0f)
    val halfW = maxOf(leftExtent, rightExtent, csPillW / 2f, btmW / 2f) + gap
    val w = halfW * 2
    val h = csPillH + gap + circleR * 2 + gap + csPillH

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = csPillH + gap + circleR

    c.drawRoundRect(cx - csPillW / 2f, 0f, cx + csPillW / 2f, csPillH, pR, pR, pillPaint)
    c.drawText(spec.callsign, cx, pV - callsignPaint.ascent(), callsignPaint)

    c.drawCircle(cx, cy, circleR, circlePaint)
    c.drawCircle(cx, cy, circleR, ringPaint)

    val bounds = android.graphics.Rect()
    glyphPaint.getTextBounds(spec.glyph, 0, spec.glyph.length, bounds)
    val glyphX = cx - (bounds.left + bounds.right) / 2f
    val glyphY = cy - (bounds.top + bounds.bottom) / 2f
    c.drawText(spec.glyph, glyphX, glyphY, glyphPaint)

    if (spec.leftValue.isNotEmpty()) {
        val pr = cx - circleR - gap
        val pl = pr - leftPW
        val pt = cy - pillH / 2f
        val pb = cy + pillH / 2f
        c.drawRoundRect(pl, pt, pr, pb, pR, pR, pillPaint)
        val ty = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f
        c.drawText(spec.leftValue, pl + pH, ty, valuePaint)
        c.drawText(spec.leftUnit, pl + pH + leftVW, ty, unitPaint)
    }

    if (spec.rightValue.isNotEmpty()) {
        val pl = cx + circleR + gap
        val pr = pl + rightPW
        val pt = cy - pillH / 2f
        val pb = cy + pillH / 2f
        c.drawRoundRect(pl, pt, pr, pb, pR, pR, pillPaint)
        val ty = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f
        c.drawText(spec.rightValue, pl + pH, ty, valuePaint)
        c.drawText(spec.rightUnit, pl + pH + rightVW, ty, unitPaint)
    }

    if (spec.bottomText.isNotEmpty()) {
        val btmTop = cy + circleR + gap
        c.drawRoundRect(cx - btmW / 2f, btmTop, cx + btmW / 2f, btmTop + csPillH, pR, pR, pillPaint)
        c.drawText(spec.bottomText, cx, btmTop + pV - btmPaint.ascent(), btmPaint)
    }

    if (savedDebugBitmap.compareAndSet(false, true)) {
        android.util.Log.i("PeerLayer", "render: first bitmap ${bmp.width}x${bmp.height}")
    }
    return bmp
}
