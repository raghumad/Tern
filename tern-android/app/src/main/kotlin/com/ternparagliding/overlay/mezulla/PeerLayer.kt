package com.ternparagliding.overlay.mezulla

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.redux.MezullaViewMode
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import java.time.Instant

private const val S = 2.5f

@Suppress("UNCHECKED_CAST")
@Composable
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

    val font = nerdFont ?: Typeface.MONOSPACE

    // One source + one layer per peer. With 1-5 peers in a buddy
    // group this is negligible overhead, and it sidesteps the
    // maplibre-compose limitation of not supporting iconImage from
    // feature properties with pre-registered named bitmaps.
    bundle.specs.forEachIndexed { i, spec ->
        val peerGeoJson = remember(bundle.geoJson, spec.imageName) {
            val json = bundle.geoJson
            val start = json.indexOf("""{"type":"Feature""", json.indexOf(spec.imageName) - 100)
            val end = json.indexOf("}", start + 1).let { first ->
                json.indexOf("}", first + 1).let { second ->
                    json.indexOf("}", second + 1) + 1
                }
            }
            if (start >= 0 && end > start) {
                """{"type":"FeatureCollection","features":[${json.substring(start, end)}]}"""
            } else {
                """{"type":"FeatureCollection","features":[]}"""
            }
        }

        val source = rememberGeoJsonSource(data = GeoJsonData.JsonString(peerGeoJson))
        val bmp = remember(spec) { renderMarkerBitmap(spec, font).asImageBitmap() }

        org.maplibre.compose.layers.SymbolLayer(
            id = "peer-${spec.imageName}",
            source = source,
            iconImage = image(bmp),
            iconAllowOverlap = const(true),
            iconSize = const(0.75f),
        )
    }
}

internal fun renderMarkerBitmap(spec: MarkerSpec, nerdFont: Typeface): Bitmap {
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

    return bmp
}
