package com.ternparagliding.overlay.mezulla

import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.redux.MezullaViewMode
import org.osmdroid.util.GeoPoint
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

internal data class PeerBundle(
    val geoJson: String,
    val specs: List<MarkerSpec>,
    /** Per-peer single-feature GeoJSON, keyed by spec.imageName. */
    val perPeerGeoJson: Map<String, String> = emptyMap(),
)

/**
 * Everything [renderMarkerBitmap] / [renderCompactBitmap] need to draw one
 * peer's HUD. Geometry-derived fields (distance, relative altitude) are
 * pre-computed here against the pilot's own position so the renderer stays
 * a pure drawing function.
 */
internal data class MarkerSpec(
    val imageName: String,
    val callsign: String,
    /** Short tag (≤3 chars) for the decluttered/compact marker. */
    val shortTag: String,
    val glyph: String,
    /** Puck fill colour, driven by staleness. */
    val puckColor: Int,
    /** Buddy track in degrees (0 = N). Null = unknown or lost → no arrow. */
    val trackDegrees: Float?,
    /** Relative altitude vs me, e.g. "+340m" / "-180m". "" when unknown. */
    val deltaAltText: String,
    val deltaAltColor: Int,
    /** Distance from me, e.g. "2.4km" / "850m". "" when own position unknown. */
    val distanceText: String,
    /** Bottom line: view-mode metric, or "⚠ STALE" / "⚠ LOST" when degraded. */
    val bottomText: String,
    val bottomColor: Int,
    val staleness: MezullaPeerTextFormatter.StalenessLevel,
)

private const val ALT_UP_COLOR = 0xFFB4FFB4.toInt()   // light green: above me
private const val ALT_DOWN_COLOR = 0xFFFFB4B4.toInt()  // light red: below me

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()}m"
    else String.format("%.1fkm", meters / 1000.0)

internal fun buildPeerBundle(
    peers: Map<Long, KnownPeer>,
    viewMode: MezullaViewMode,
    now: Instant,
    ownLocation: GeoPoint? = null,
    altitudeUnit: String = "m",
): PeerBundle {
    val withPos = peers.entries.filter { it.value.lastPosition != null }
    android.util.Log.i("PeerBundle",
        "build: peers.total=${peers.size}, withPos=${withPos.size}, viewMode=$viewMode, ownPos=${ownLocation != null}")
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
        val lost = staleness == MezullaPeerTextFormatter.StalenessLevel.LOST

        val glyph: String
        val puckColor: Int
        when (staleness) {
            MezullaPeerTextFormatter.StalenessLevel.FRESH -> {
                glyph = MezullaIcons.PEER; puckColor = 0xFF4CAF50.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.AGING -> {
                glyph = MezullaIcons.PEER; puckColor = 0xFFFFD600.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.STALE -> {
                glyph = MezullaIcons.PEER; puckColor = 0xFFFF9100.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.LOST -> {
                glyph = MezullaIcons.PEER_LOST; puckColor = 0xFF9E9E9E.toInt()
            }
        }

        // Track arrow: only when we have a heading and the peer isn't lost.
        val trackDegrees = if (lost) null else fix.groundTrackDegrees?.toFloat()

        // Relative altitude vs me (left pill).
        var deltaAltText = ""
        var deltaAltColor = ALT_UP_COLOR
        if (!lost && ownLocation != null && fix.altitudeMeters != null) {
            val deltaM = fix.altitudeMeters - ownLocation.altitude
            val delta = com.ternparagliding.units.Units.altitudeValue(deltaM, altitudeUnit).roundToInt()
            val sym = com.ternparagliding.units.Units.altitudeSymbol(altitudeUnit)
            deltaAltText = "${if (delta >= 0) "+" else ""}$delta$sym"
            deltaAltColor = if (delta >= 0) ALT_UP_COLOR else ALT_DOWN_COLOR
        }

        // Distance from me (right pill).
        var distanceText = ""
        if (!lost && ownLocation != null) {
            val meters = ownLocation.distanceToAsDouble(
                GeoPoint(fix.latitudeDeg, fix.longitudeDeg)
            )
            distanceText = formatDistance(meters)
        }

        // Bottom line: status when degraded, else the view-mode metric.
        val bottomText: String
        val bottomColor: Int
        when (staleness) {
            MezullaPeerTextFormatter.StalenessLevel.STALE -> {
                bottomText = "⚠ STALE"; bottomColor = 0xFFFF9100.toInt()
            }
            MezullaPeerTextFormatter.StalenessLevel.LOST -> {
                bottomText = "⚠ LOST"; bottomColor = 0xFF9E9E9E.toInt()
            }
            else -> {
                bottomColor = 0xFFFFFFFF.toInt()
                bottomText = when (viewMode) {
                    MezullaViewMode.SAFETY -> {
                        val age = Duration.between(peer.lastSeenAt, now).seconds
                        if (age < 60) "${age}s ago" else "${age / 60}m ago"
                    }
                    MezullaViewMode.CLIMB -> {
                        val climb = peer.climbRateMs ?: 0.0
                        "${if (climb >= 0) "+" else ""}${String.format("%.1f", climb)} m/s"
                    }
                    MezullaViewMode.TACTICAL -> {
                        fix.groundSpeedMetersPerSecond?.let {
                            "${String.format("%.0f", it * 3.6)} km/h"
                        } ?: ""
                    }
                }
            }
        }

        specs.add(
            MarkerSpec(
                imageName = imageName,
                callsign = callsign,
                shortTag = callsign.take(3),
                glyph = glyph,
                puckColor = puckColor,
                trackDegrees = trackDegrees,
                deltaAltText = deltaAltText,
                deltaAltColor = deltaAltColor,
                distanceText = distanceText,
                bottomText = bottomText,
                bottomColor = bottomColor,
                staleness = staleness,
            )
        )

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
