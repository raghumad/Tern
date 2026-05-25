package com.madanala.tern.overlay.mezulla

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.madanala.tern.ui.theme.LocalTernTextStyles
import androidx.compose.ui.unit.sp
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.overlay.priority.Position as TernPosition
import com.madanala.tern.redux.MezullaViewMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import java.time.Instant

/**
 * Renders Mezulla peer markers on the map.
 *
 * Two layers work together:
 * 1. MapLibre CircleLayer (inside MaplibreMap content) — blue dots at peer positions
 * 2. Compose text overlays (outside MaplibreMap, on top) — "CBE · 3120m · 5s ago"
 *
 * SymbolLayer text rendering is broken in maplibre-compose 0.13.0, so we use
 * Compose Text positioned via CameraProjection as a workaround. The text is
 * Compose-native, so test matchers can find it.
 */

// -- Part 1: CircleLayer (called inside MaplibreMap { } content block) --

@Composable
fun MezullaPeerCircles(
    peers: Map<Long, KnownPeer>,
    now: Instant,
) {
    val geoJsonString = remember(peers, now) {
        buildCircleGeoJson(peers)
    }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.JsonString(geoJsonString),
    )

    org.maplibre.compose.layers.CircleLayer(
        id = "mezulla-peer-circles",
        source = source,
        color = const(MezullaTheme.Circle.fillColor),
        radius = const(MezullaTheme.Circle.radius),
        strokeColor = const(MezullaTheme.Circle.strokeColor),
        strokeWidth = const(MezullaTheme.Circle.strokeWidth),
    )
}

// -- Part 2: Text overlays (called outside MaplibreMap, as Compose siblings) --

@Composable
fun MezullaPeerLabels(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    pilotPosition: TernPosition?,
    now: Instant,
    cameraState: CameraState,
) {
    val projection = cameraState.projection ?: return
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        peers.values.forEach { peer ->
            val fix = peer.lastPosition ?: return@forEach
            val staleness = MezullaPeerTextFormatter.computeStaleness(peer, now)
            val displayText = MezullaPeerTextFormatter.displayText(
                peer = peer,
                fix = fix,
                viewMode = viewMode,
                staleness = staleness,
                pilotPosition = pilotPosition,
                now = now,
            )
            val callsign = MezullaPeerTextFormatter.callsign(peer)
            val stalenessColor = when (staleness) {
                MezullaPeerTextFormatter.StalenessLevel.FRESH -> MezullaTheme.StalenessColors.fresh
                MezullaPeerTextFormatter.StalenessLevel.AGING -> MezullaTheme.StalenessColors.aging
                MezullaPeerTextFormatter.StalenessLevel.STALE -> MezullaTheme.StalenessColors.stale
                MezullaPeerTextFormatter.StalenessLevel.LOST -> MezullaTheme.StalenessColors.lost
            }
            val opacity = when (staleness) {
                MezullaPeerTextFormatter.StalenessLevel.FRESH -> MezullaTheme.StalenessOpacity.fresh
                MezullaPeerTextFormatter.StalenessLevel.AGING -> MezullaTheme.StalenessOpacity.aging
                MezullaPeerTextFormatter.StalenessLevel.STALE -> MezullaTheme.StalenessOpacity.stale
                MezullaPeerTextFormatter.StalenessLevel.LOST -> MezullaTheme.StalenessOpacity.lost
            }

            val screenPos = projection.screenLocationFromPosition(
                Position(fix.longitudeDeg, fix.latitudeDeg)
            )
            val xPx = with(density) { screenPos.x.toPx().toInt() }
            val yPx = with(density) { screenPos.y.toPx().toInt() }

            val textStyles = LocalTernTextStyles.current

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .offset { IntOffset(xPx - 50, yPx - 60) }
                    .testTag("peer_label_$callsign")
                    .background(
                        MezullaTheme.Label.haloColor.copy(alpha = 0.85f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = displayText,
                    color = stalenessColor.copy(alpha = opacity),
                    style = textStyles.mapLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// -- GeoJSON builders --

private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

private fun buildCircleGeoJson(peers: Map<Long, KnownPeer>): String {
    val withPosition = peers.values.filter { it.lastPosition != null }
    if (withPosition.isEmpty()) return EMPTY_COLLECTION

    val sb = StringBuilder(withPosition.size * 100)
    sb.append("""{"type":"FeatureCollection","features":[""")
    withPosition.forEachIndexed { i, peer ->
        val fix = peer.lastPosition!!
        if (i > 0) sb.append(",")
        sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${fix.longitudeDeg},${fix.latitudeDeg}]},"properties":{}}""")
    }
    sb.append("]}")
    return sb.toString()
}

internal fun buildPeerFeatureCollection(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    pilotPosition: TernPosition?,
    now: Instant,
): FeatureCollection<Point, JsonObject> {
    val features = peers.values.mapNotNull { peer ->
        val fix = peer.lastPosition ?: return@mapNotNull null
        val staleness = MezullaPeerTextFormatter.computeStaleness(peer, now)
        val displayText = MezullaPeerTextFormatter.displayText(
            peer = peer, fix = fix, viewMode = viewMode,
            staleness = staleness, pilotPosition = pilotPosition, now = now,
        )
        val point = Point(fix.longitudeDeg, fix.latitudeDeg)
        val properties = JsonObject(mapOf(
            "displayText" to JsonPrimitive(displayText),
            "staleness" to JsonPrimitive(staleness.name),
            "callsign" to JsonPrimitive(MezullaPeerTextFormatter.callsign(peer)),
        ))
        Feature(point, properties)
    }
    return FeatureCollection(features)
}

fun buildPeerOverlayCandidates(
    peers: Map<Long, KnownPeer>,
): List<PeerOverlayCandidate> =
    peers.mapNotNull { (nodeNumber, peer) ->
        val fix = peer.lastPosition ?: return@mapNotNull null
        PeerOverlayCandidate(
            position = TernPosition(fix.latitudeDeg, fix.longitudeDeg),
            nodeNumber = nodeNumber,
        )
    }
