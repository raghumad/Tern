package com.madanala.tern.overlay.mezulla

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.overlay.priority.Position as TernPosition
import com.madanala.tern.redux.MezullaViewMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import java.time.Instant

@Composable
fun MezullaPeerLayer(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    pilotPosition: TernPosition?,
    now: Instant,
) {
    val featureCollection = remember(peers, viewMode, pilotPosition, now) {
        buildPeerFeatureCollection(peers, viewMode, pilotPosition, now)
    }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(featureCollection),
    )

    @Suppress("UNCHECKED_CAST")
    val textExpr = feature.get("displayText") as Expression<StringValue>

    org.maplibre.compose.layers.SymbolLayer(
        id = "mezulla-peer-layer",
        source = source,
        textField = textExpr,
        textSize = const(14.sp),
        textColor = const(Color.White),
        textHaloColor = const(Color(0xCC000000)),
        textHaloWidth = const(2.dp),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
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
            peer = peer,
            fix = fix,
            viewMode = viewMode,
            staleness = staleness,
            pilotPosition = pilotPosition,
            now = now,
        )

        val point = Point(fix.longitudeDeg, fix.latitudeDeg)
        val properties = JsonObject(
            mapOf(
                "displayText" to JsonPrimitive(displayText),
                "staleness" to JsonPrimitive(staleness.name),
                "callsign" to JsonPrimitive(MezullaPeerTextFormatter.callsign(peer)),
                "nodeNumber" to JsonPrimitive(peer.identity.nodeNumber),
            )
        )

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
