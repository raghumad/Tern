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
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.ast.Expression
import androidx.compose.runtime.key
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
        buildPeerFeatureCollection(peers, viewMode, pilotPosition, now).also { fc ->
            android.util.Log.d("MezullaPeerLayer", "peers=${peers.size} features=${fc.features.size} viewMode=$viewMode")
            fc.features.firstOrNull()?.let { f ->
                val geom = f.geometry
                android.util.Log.d("MezullaPeerLayer", "first feature: geom=$geom props=${f.properties}")
            }
        }
    }

    // Build GeoJSON string and use GeoJsonData.Literal for the source.
    // rememberGeoJsonSource with Features() doesn't propagate state changes.
    val geoJsonString = remember(featureCollection) {
        if (featureCollection.features.isEmpty()) {
            """{"type":"FeatureCollection","features":[]}"""
        } else {
            val sb = StringBuilder()
            sb.append("""{"type":"FeatureCollection","features":[""")
            featureCollection.features.forEachIndexed { i, f ->
                if (i > 0) sb.append(",")
                val geom = f.geometry
                val lon = (geom as? org.maplibre.spatialk.geojson.Point)?.longitude ?: 0.0
                val lat = (geom as? org.maplibre.spatialk.geojson.Point)?.latitude ?: 0.0
                val props = f.properties?.toString() ?: "{}"
                sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":$props}""")
            }
            sb.append("]}")
            sb.toString()
        }.also { android.util.Log.d("MezullaPeerLayer", "GeoJSON: ${it.take(200)}") }
    }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.JsonString(geoJsonString),
    )

    // Blue circles as peer position markers
    org.maplibre.compose.layers.CircleLayer(
        id = "mezulla-peer-circles",
        source = source,
        color = const(Color(0xFF2196F3)),
        radius = const(12.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
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
