package com.ternparagliding.overlay.mezulla

import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.redux.MezullaViewMode
import java.time.Duration
import java.time.Instant

internal data class PeerBundle(
    val geoJson: String,
    val specs: List<MarkerSpec>,
    /** Per-peer single-feature GeoJSON, keyed by spec.imageName. */
    val perPeerGeoJson: Map<String, String> = emptyMap(),
)

internal data class MarkerSpec(
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
    val staleness: MezullaPeerTextFormatter.StalenessLevel,
)

internal fun buildPeerBundle(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    now: Instant,
): PeerBundle {
    val withPos = peers.entries.filter { it.value.lastPosition != null }
    android.util.Log.i("PeerBundle",
        "build: peers.total=${peers.size}, withPos=${withPos.size}, viewMode=$viewMode")
    if (withPos.isEmpty()) {
        return PeerBundle("""{"type":"FeatureCollection","features":[]}""", emptyList())
    }

    val specs = mutableListOf<MarkerSpec>()
    val perPeerGeoJson = mutableMapOf<String, String>()
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
            leftValue, leftUnit, rightValue, rightUnit, bottomText, bottomColor,
            staleness))

        val feature = """{"type":"Feature","geometry":{"type":"Point","coordinates":[${fix.longitudeDeg},${fix.latitudeDeg}]},"properties":{"markerImage":"$imageName","staleness":"${staleness.name}"}}"""
        if (i > 0) sb.append(",")
        sb.append(feature)
        perPeerGeoJson[imageName] = """{"type":"FeatureCollection","features":[$feature]}"""
    }

    sb.append("]}")
    val result = sb.toString()
    android.util.Log.i("PeerBundle", "built ${specs.size} peers")
    return PeerBundle(result, specs, perPeerGeoJson)
}
