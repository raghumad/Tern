package com.ternparagliding.overlay.mezulla

import com.ternparagliding.mezulla.redux.KnownPeer
import org.osmdroid.util.GeoPoint
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PeerBundle(
    val geoJson: String,
    val specs: List<MarkerSpec>,
    /** Per-peer single-feature GeoJSON, keyed by spec.imageName. */
    val perPeerGeoJson: Map<String, String> = emptyMap(),
)

/**
 * How much of a buddy's HUD to draw. There are no view modes any more — every
 * marker shows the full picture and declutters automatically when the screen
 * gets busy, dropping the least safety-critical fields first.
 *
 * Drop order (richest → sparsest): ground speed, then distance. Relative
 * altitude, climb, and freshness (the collision/gaggle-critical reads) are kept
 * the longest. The zoomed-out compact puck ([renderCompactBitmap]) is a separate
 * zoom-driven state below this ladder.
 */
internal enum class DeclutterLevel { FULL, MEDIUM, REDUCED }

/**
 * Everything [renderMarkerBitmap] / [renderCompactBitmap] need to draw one
 * peer's HUD. Geometry-derived fields (distance, relative altitude) are
 * pre-computed here against the pilot's own position so the renderer stays
 * a pure drawing function. Fields the current [level] drops are left empty,
 * and the renderer simply skips empty slots.
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
    /** Distance from me, e.g. "2.4km" / "850m". "" when unknown or dropped by declutter. */
    val distanceText: String,
    /** Bottom line: climb (+ ground speed when uncluttered), or "⚠ STALE" / "⚠ LOST". */
    val bottomText: String,
    val bottomColor: Int,
    val staleness: MezullaPeerTextFormatter.StalenessLevel,
    val level: DeclutterLevel,
)

private const val ALT_UP_COLOR = 0xFFB4FFB4.toInt()   // light green: above me
private const val ALT_DOWN_COLOR = 0xFFFFB4B4.toInt()  // light red: below me

// Declutter thresholds. With few buddies everyone stays FULL; as the roster
// grows, the nearest two keep the full HUD (proximity wins) and the rest shed
// fields by distance rank.
private const val FEW_PEERS = 3
private const val KEEP_NEAREST_FULL = 2
private const val MEDIUM_UNTIL_RANK = 5

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()}m"
    else String.format("%.1fkm", meters / 1000.0)

/** Climb rate as a compact vario read: "▲1.2" / "▼0.8" (m/s implied by the arrow). "" when unknown. */
private fun climbText(climbMs: Double?): String =
    climbMs?.let { "${if (it >= 0) "▲" else "▼"}${String.format("%.1f", abs(it))}" } ?: ""

internal fun buildPeerBundle(
    peers: Map<Long, KnownPeer>,
    now: Instant,
    ownLocation: GeoPoint? = null,
    altitudeUnit: String = "m",
): PeerBundle {
    val withPos = peers.entries.filter { it.value.lastPosition != null }
    android.util.Log.i("PeerBundle",
        "build: peers.total=${peers.size}, withPos=${withPos.size}, ownPos=${ownLocation != null}")
    if (withPos.isEmpty()) {
        return PeerBundle("""{"type":"FeatureCollection","features":[]}""", emptyList())
    }

    // Rank buddies by distance to the pilot (nearest = 0) so proximity can keep
    // the closest ones fully detailed while far ones declutter. No own fix → no
    // ranking (and no distance/relative-altitude to show anyway).
    val rankByNode: Map<Long, Int> = if (ownLocation != null) {
        withPos.sortedBy { (_, peer) ->
            peer.lastPosition!!.let { ownLocation.distanceToAsDouble(GeoPoint(it.latitudeDeg, it.longitudeDeg)) }
        }.mapIndexed { idx, (node, _) -> node to idx }.toMap()
    } else {
        emptyMap()
    }
    val n = withPos.size
    fun levelFor(nodeNum: Long): DeclutterLevel {
        if (ownLocation == null || n <= FEW_PEERS) return DeclutterLevel.FULL
        return when (rankByNode[nodeNum] ?: Int.MAX_VALUE) {
            in 0 until KEEP_NEAREST_FULL -> DeclutterLevel.FULL
            in KEEP_NEAREST_FULL until MEDIUM_UNTIL_RANK -> DeclutterLevel.MEDIUM
            else -> DeclutterLevel.REDUCED
        }
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
        val level = levelFor(nodeNum)

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

        // Relative altitude vs me (left pill) — kept at every declutter level.
        var deltaAltText = ""
        var deltaAltColor = ALT_UP_COLOR
        if (!lost && ownLocation != null && fix.altitudeMeters != null) {
            val deltaM = fix.altitudeMeters - ownLocation.altitude
            val delta = com.ternparagliding.units.Units.altitudeValue(deltaM, altitudeUnit).roundToInt()
            val sym = com.ternparagliding.units.Units.altitudeSymbol(altitudeUnit)
            deltaAltText = "${if (delta >= 0) "+" else ""}$delta$sym"
            deltaAltColor = if (delta >= 0) ALT_UP_COLOR else ALT_DOWN_COLOR
        }

        // Distance from me (right pill) — dropped at REDUCED.
        var distanceText = ""
        if (!lost && ownLocation != null && level != DeclutterLevel.REDUCED) {
            val meters = ownLocation.distanceToAsDouble(GeoPoint(fix.latitudeDeg, fix.longitudeDeg))
            distanceText = formatDistance(meters)
        }

        // Bottom line: status when degraded, else climb (+ ground speed when uncluttered).
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
                val climb = climbText(peer.climbRateMs)
                // Ground speed only when there's room (FULL); it's the first field to shed.
                val speed = if (level == DeclutterLevel.FULL) {
                    fix.groundSpeedMetersPerSecond?.let { "${(it * 3.6).roundToInt()}km/h" } ?: ""
                } else ""
                bottomText = listOf(climb, speed).filter { it.isNotEmpty() }.joinToString("  ")
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
                level = level,
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
