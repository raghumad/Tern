package com.madanala.tern.overlay

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.util.Log
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
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Instant

private const val TAG = "NativeMap"
private const val PEER_SOURCE = "mezulla-peers"
private const val PEER_LAYER = "mezulla-peer-labels"
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Native MapLibre map with local font rendering (offline-first).
 *
 * Uses [MapLibreMapOptions.localIdeographFontFamily] to render ALL
 * text using the device's local font instead of downloading SDF glyph
 * bitmaps from a server. No network dependency for text rendering.
 *
 * Everything on the map is a GeoJSON feature rendered by native layers.
 */
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
    var nativeMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapStyle by remember { mutableStateOf<Style?>(null) }

    val peerGeoJson = remember(peers, viewMode, lastEventTime) {
        buildPeerGeoJson(peers, viewMode, lastEventTime)
    }

    AndroidView(
        factory = { ctx ->
            MapLibre.getInstance(ctx)

            // Local font rendering — no glyph server needed.
            // MapLibre will use this font for ALL text instead of
            // downloading SDF PBF files from a URL.
            val options = MapLibreMapOptions.createFromAttributes(ctx)
                .localIdeographFontFamilyEnabled(true)
                .localIdeographFontFamily("sans-serif")

            MapView(ctx, options).apply {
                getMapAsync { map ->
                    nativeMap = map
                    map.setStyle(STYLE_URL) { style ->
                        mapStyle = style
                        Log.d(TAG, "Style loaded (local font rendering)")

                        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(initialLat, initialLon))
                                .zoom(initialZoom)
                                .build()
                        ))

                        // Peer SymbolLayer
                        style.addSource(GeoJsonSource(PEER_SOURCE, peerGeoJson))
                        val layer = SymbolLayer(PEER_LAYER, PEER_SOURCE)
                        layer.setProperties(
                            textField(get("displayText")),
                            textFont(arrayOf("Noto Sans Regular")),
                            textSize(14f),
                            textColor(AndroidColor.WHITE),
                            textHaloColor(AndroidColor.BLACK),
                            textHaloWidth(1.5f),
                            textAllowOverlap(true),
                            textIgnorePlacement(true),
                        )
                        style.addLayer(layer)
                        Log.d(TAG, "Peer SymbolLayer added")

                        onMapReady?.invoke(map)
                    }
                }
            }
        },
        modifier = modifier,
    )

    // Update peer GeoJSON when data changes
    LaunchedEffect(peerGeoJson, mapStyle) {
        val style = mapStyle ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(PEER_SOURCE)
        if (source != null) {
            source.setGeoJson(peerGeoJson)
            Log.d(TAG, "Peer GeoJSON updated: ${peers.size} peers")
        }
    }
}

private fun buildPeerGeoJson(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    now: Instant,
): String {
    val withPosition = peers.values.filter { it.lastPosition != null }
    if (withPosition.isEmpty()) {
        return """{"type":"FeatureCollection","features":[]}"""
    }

    val sb = StringBuilder(withPosition.size * 300)
    sb.append("""{"type":"FeatureCollection","features":[""")

    withPosition.forEachIndexed { i, peer ->
        val fix = peer.lastPosition!!
        val staleness = MezullaPeerTextFormatter.computeStaleness(peer, now)
        val callsign = MezullaPeerTextFormatter.callsign(peer)
        val detailLine = MezullaPeerTextFormatter.detailLine(
            peer = peer, fix = fix, viewMode = viewMode,
            staleness = staleness, pilotPosition = null, now = now,
        )
        val displayText = "${callsign.uppercase()}\\n${detailLine}"
            .replace("\"", "\\\"")

        if (i > 0) sb.append(",")
        sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${fix.longitudeDeg},${fix.latitudeDeg}]},"properties":{"displayText":"$displayText","callsign":"$callsign","staleness":"${staleness.name}"}}""")
    }

    sb.append("]}")
    return sb.toString()
}
