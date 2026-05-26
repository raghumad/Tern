package com.madanala.tern.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.madanala.tern.R
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.overlay.mezulla.MezullaIcons
import com.madanala.tern.overlay.mezulla.MezullaPeerTextFormatter
import com.madanala.tern.redux.MezullaViewMode
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlinx.coroutines.delay
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Duration
import java.time.Instant

private const val TAG = "NativeMap"
private const val PEER_SOURCE = "mezulla-peers"
private const val PEER_LAYER = "mezulla-peers"
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val S = 2.5f // render scale

@Composable
fun NativeMapView(
    modifier: Modifier = Modifier,
    initialLat: Double = 45.8,
    initialLon: Double = 6.5,
    initialZoom: Double = 10.0,
    peers: Map<Long, KnownPeer> = emptyMap(),
    viewMode: MezullaViewMode = MezullaViewMode.SAFETY,
    lastEventTime: Instant = Instant.EPOCH,
    onMapReady: ((MapLibreMap) -> Unit)? = null,
) {
    val context = LocalContext.current
    var mapStyle by remember { mutableStateOf<Style?>(null) }
    var nerdFont by remember { mutableStateOf<Typeface?>(null) }

    val bundle = remember(peers, viewMode, lastEventTime) {
        buildPeerBundle(peers, viewMode, lastEventTime)
    }

    AndroidView(
        factory = { ctx ->
            MapLibre.getInstance(ctx)
            val options = MapLibreMapOptions.createFromAttributes(ctx)
                .localIdeographFontFamilyEnabled(true)
                .localIdeographFontFamily("JetBrains Mono Nerd Font", "sans-serif")

            MapView(ctx, options).apply {
                getMapAsync { map ->
                    map.setStyle(STYLE_URL) { style ->
                        mapStyle = style
                        Log.d(TAG, "Style loaded")

                        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(initialLat, initialLon))
                                .zoom(initialZoom)
                                .build()
                        ))

                        nerdFont = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_nerd_regular)

                        style.addSource(GeoJsonSource(PEER_SOURCE, bundle.geoJson))
                        style.addLayer(SymbolLayer(PEER_LAYER, PEER_SOURCE).apply {
                            setProperties(
                                iconImage(get("markerImage")),
                                iconAllowOverlap(true),
                                iconIgnorePlacement(true),
                                iconAnchor("center"),
                                iconSize(0.75f),
                            )
                        })

                        nerdFont?.let { font ->
                            registerMarkerBitmaps(style, bundle.specs, font)
                        }

                        onMapReady?.invoke(map)
                    }
                }
            }
        },
        modifier = modifier,
    )

    LaunchedEffect(bundle, mapStyle, nerdFont) {
        val style = mapStyle ?: return@LaunchedEffect
        val font = nerdFont ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(PEER_SOURCE) ?: return@LaunchedEffect

        registerMarkerBitmaps(style, bundle.specs, font)
        source.setGeoJson(bundle.geoJson)
        Log.d(TAG, "Peer markers updated: ${bundle.specs.size} peers")
    }

    // Opacity pulse for stale/lost peers
    LaunchedEffect(mapStyle) {
        val style = mapStyle ?: return@LaunchedEffect
        var on = false
        while (true) {
            delay(600)
            on = !on
            val layer = style.getLayerAs<SymbolLayer>(PEER_LAYER) ?: continue
            val staleAlpha = if (on) 0.45f else 1.0f
            val lostAlpha = if (on) 0.25f else 0.7f
            try {
                layer.setProperties(
                    iconOpacity(
                        match(
                            get("staleness"),
                            literal(1.0f),
                            literal("STALE"), literal(staleAlpha),
                            literal("LOST"), literal(lostAlpha),
                        )
                    )
                )
            } catch (_: Exception) {}
        }
    }
}

// -- Data --

private data class PeerBundle(val geoJson: String, val specs: List<MarkerSpec>)

private data class MarkerSpec(
    val imageName: String,
    val callsign: String,
    val glyph: String,
    val glyphColor: Int,
    val leftValue: String,
    val leftUnit: String,
    val rightValue: String,
    val rightUnit: String,
    val bottomText: String,
    val bottomColor: Int,
)

// -- GeoJSON + spec builder --

private fun buildPeerBundle(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    now: Instant,
): PeerBundle {
    val withPos = peers.entries.filter { it.value.lastPosition != null }
    if (withPos.isEmpty()) {
        return PeerBundle("""{"type":"FeatureCollection","features":[]}""", emptyList())
    }

    val specs = mutableListOf<MarkerSpec>()
    val sb = StringBuilder(withPos.size * 200)
    sb.append("""{"type":"FeatureCollection","features":[""")

    withPos.forEachIndexed { i, (nodeNum, peer) ->
        val fix = peer.lastPosition!!
        val staleness = MezullaPeerTextFormatter.computeStaleness(peer, now)
        val callsign = MezullaPeerTextFormatter.callsign(peer).uppercase()
        val imageName = "peer-$nodeNum"

        val glyph: String
        val glyphColor: Int
        when (staleness) {
            MezullaPeerTextFormatter.StalenessLevel.FRESH -> {
                glyph = MezullaIcons.PEER; glyphColor = 0xFF4CAF50.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.AGING -> {
                glyph = MezullaIcons.PEER; glyphColor = 0xFFFFD600.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.STALE -> {
                glyph = MezullaIcons.PEER; glyphColor = 0xFFFF9100.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.LOST -> {
                glyph = MezullaIcons.PEER_LOST; glyphColor = 0xFF9E9E9E.toInt()
            }
        }

        val leftValue: String
        val leftUnit: String
        val rightValue: String
        val rightUnit: String
        if (staleness == MezullaPeerTextFormatter.StalenessLevel.LOST) {
            leftValue = "lost"; leftUnit = ""; rightValue = ""; rightUnit = ""
        } else {
            val age = Duration.between(peer.lastSeenAt, now).seconds
            when (viewMode) {
                MezullaViewMode.SAFETY -> {
                    leftValue = if (age < 60) "$age" else "${age / 60}"
                    leftUnit = if (age < 60) "s" else "m"
                    rightValue = fix.altitudeMeters?.toString() ?: "---"
                    rightUnit = if (fix.altitudeMeters != null) "m" else ""
                }
                MezullaViewMode.CLIMB -> {
                    val climb = peer.climbRateMs ?: 0.0
                    leftValue = "${if (climb >= 0) "+" else ""}${String.format("%.1f", climb)}"
                    leftUnit = "m/s"
                    rightValue = fix.altitudeMeters?.toString() ?: "---"
                    rightUnit = if (fix.altitudeMeters != null) "m" else ""
                }
                MezullaViewMode.TACTICAL -> {
                    leftValue = fix.groundSpeedMetersPerSecond?.let {
                        String.format("%.0f", it * 3.6)
                    } ?: "---"
                    leftUnit = if (fix.groundSpeedMetersPerSecond != null) "km/h" else ""
                    rightValue = fix.altitudeMeters?.toString() ?: "---"
                    rightUnit = if (fix.altitudeMeters != null) "m" else ""
                }
            }
        }

        val bottomText: String
        val bottomColor: Int
        when (staleness) {
            MezullaPeerTextFormatter.StalenessLevel.STALE -> {
                bottomText = "⚠ STALE"; bottomColor = 0xFFFF9100.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.LOST -> {
                bottomText = "⚠ LOST"; bottomColor = 0xFF9E9E9E.toInt()
            }
            else -> { bottomText = ""; bottomColor = 0 }
        }

        specs.add(MarkerSpec(imageName, callsign, glyph, glyphColor,
            leftValue, leftUnit, rightValue, rightUnit, bottomText, bottomColor))

        if (i > 0) sb.append(",")
        sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${fix.longitudeDeg},${fix.latitudeDeg}]},"properties":{"markerImage":"$imageName","staleness":"${staleness.name}"}}""")
    }

    sb.append("]}")
    return PeerBundle(sb.toString(), specs)
}

// -- Bitmap registration --

private fun registerMarkerBitmaps(style: Style, specs: List<MarkerSpec>, font: Typeface) {
    specs.forEach { spec ->
        val bitmap = renderMarkerBitmap(spec, font)
        try { style.removeImage(spec.imageName) } catch (_: Exception) {}
        style.addImage(spec.imageName, bitmap)
    }
}

// -- Composite marker renderer --
//
//        [ CALLSIGN ]
//  ┌────┐  ┌──────┐  ┌──────┐
//  │ 5s │  │  󰀂  │  │1970m │
//  └────┘  └──────┘  └──────┘
//
// Everything drawn to a single Bitmap via Android Canvas.

private fun renderMarkerBitmap(spec: MarkerSpec, nerdFont: Typeface): Bitmap {
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

    // Measure
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

    // Bottom row
    val btmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.bottomColor
        textSize = 10f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val btmW = if (spec.bottomText.isNotEmpty()) btmPaint.measureText(spec.bottomText) + pH * 2 else 0f

    // Symmetric: circle at bitmap center
    val leftExtent = circleR + (if (leftPW > 0) gap + leftPW else 0f)
    val rightExtent = circleR + (if (rightPW > 0) gap + rightPW else 0f)
    val halfW = maxOf(leftExtent, rightExtent, csPillW / 2f, btmW / 2f) + gap
    val w = halfW * 2
    val h = csPillH + gap + circleR * 2 + gap + csPillH

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = csPillH + gap + circleR

    // Callsign pill
    c.drawRoundRect(cx - csPillW / 2f, 0f, cx + csPillW / 2f, csPillH, pR, pR, pillPaint)
    c.drawText(spec.callsign, cx, pV - callsignPaint.ascent(), callsignPaint)

    // Circle + ring
    c.drawCircle(cx, cy, circleR, circlePaint)
    c.drawCircle(cx, cy, circleR, ringPaint)

    // Glyph — use getTextBounds for precise visual centering
    val bounds = android.graphics.Rect()
    glyphPaint.getTextBounds(spec.glyph, 0, spec.glyph.length, bounds)
    val glyphX = cx - (bounds.left + bounds.right) / 2f
    val glyphY = cy - (bounds.top + bounds.bottom) / 2f
    c.drawText(spec.glyph, glyphX, glyphY, glyphPaint)

    // Left pill
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

    // Right pill
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

    // Bottom pill (warning for degraded peers)
    if (spec.bottomText.isNotEmpty()) {
        val btmTop = cy + circleR + gap
        c.drawRoundRect(cx - btmW / 2f, btmTop, cx + btmW / 2f, btmTop + csPillH, pR, pR, pillPaint)
        c.drawText(spec.bottomText, cx, btmTop + pV - btmPaint.ascent(), btmPaint)
    }

    return bmp
}
