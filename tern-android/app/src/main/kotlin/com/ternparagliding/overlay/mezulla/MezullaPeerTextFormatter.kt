package com.ternparagliding.overlay.mezulla

import com.ternparagliding.mezulla.connection.PeerPosition
import com.ternparagliding.mezulla.redux.KnownPeer
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.redux.MezullaViewMode
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-logic text formatter for Mezulla peer markers. No Android, no
 * MapLibre, no UI framework dependency — just strings in, strings out.
 *
 * Extracted from the OSMDroid [MezullaOverlayManager] so the same
 * staleness thresholds and view-mode formatting drive the MapLibre
 * SymbolLayer without duplication.
 */
object MezullaPeerTextFormatter {

    // Staleness thresholds (seconds)
    const val FRESH_THRESHOLD_SECONDS = 30L
    const val AGING_THRESHOLD_SECONDS = 120L   // 2 minutes
    const val STALE_THRESHOLD_SECONDS = 300L   // 5 minutes

    enum class StalenessLevel {
        FRESH,    // < 30s
        AGING,    // 30s - 2min
        STALE,    // 2min - 5min
        LOST,     // 5min+
    }

    fun computeStaleness(peer: KnownPeer, now: Instant): StalenessLevel {
        val ageSeconds = Duration.between(peer.lastSeenAt, now).seconds
        return when {
            ageSeconds < FRESH_THRESHOLD_SECONDS -> StalenessLevel.FRESH
            ageSeconds < AGING_THRESHOLD_SECONDS -> StalenessLevel.AGING
            ageSeconds < STALE_THRESHOLD_SECONDS -> StalenessLevel.STALE
            else -> StalenessLevel.LOST
        }
    }

    /**
     * Opacity for the peer marker based on staleness. Higher staleness
     * = lower opacity. The pilot must still see stale peers (safety),
     * but the visual weight communicates freshness at a glance.
     */
    fun opacityForStaleness(staleness: StalenessLevel): Float = when (staleness) {
        StalenessLevel.FRESH -> 1.0f
        StalenessLevel.AGING -> 0.7f
        StalenessLevel.STALE -> 0.4f
        StalenessLevel.LOST -> 0.25f
    }

    /**
     * Text color hex string for staleness. Used as a GeoJSON feature
     * property that drives the SymbolLayer's `textColor`.
     *
     * Fresh: white. Aging: yellow. Stale: amber. Lost: gray.
     */
    fun colorHexForStaleness(staleness: StalenessLevel): String = when (staleness) {
        StalenessLevel.FRESH -> "#FFFFFF"
        StalenessLevel.AGING -> "#FFD600"
        StalenessLevel.STALE -> "#FF8F00"
        StalenessLevel.LOST -> "#9E9E9E"
    }

    /**
     * The callsign to show. Falls back from longName → shortName → hexId.
     */
    fun callsign(peer: KnownPeer): String =
        peer.identity.longName
            ?: peer.identity.shortName
            ?: peer.identity.hexId

    /**
     * Just the detail line (no callsign). Used when icon + callsign
     * are rendered separately from the detail text.
     */
    fun detailLine(
        peer: KnownPeer,
        fix: PeerPosition.Fix,
        viewMode: MezullaViewMode,
        staleness: StalenessLevel,
        pilotPosition: Position?,
        now: Instant,
    ): String {
        if (staleness == StalenessLevel.LOST) return "lost contact"
        val warning = if (staleness == StalenessLevel.STALE) " ${MezullaIcons.WARNING}" else ""
        return formatSecondLine(peer, fix, viewMode, pilotPosition, now) + warning
    }

    /**
     * The full display text for a peer marker (callsign + second line),
     * formatted for the active view mode and staleness.
     */
    fun displayText(
        peer: KnownPeer,
        fix: PeerPosition.Fix,
        viewMode: MezullaViewMode,
        staleness: StalenessLevel,
        pilotPosition: Position?,
        now: Instant,
    ): String {
        val name = callsign(peer)
        val secondLine = if (staleness == StalenessLevel.LOST) {
            "lost contact"
        } else {
            formatSecondLine(peer, fix, viewMode, pilotPosition, now)
        }
        val warning = if (staleness == StalenessLevel.STALE) " ⚠" else ""
        return "$name\n$secondLine$warning"
    }

    /**
     * Format just the second line (below the callsign) for the given
     * view mode. Exposed for testing independently of [displayText].
     */
    fun formatSecondLine(
        peer: KnownPeer,
        fix: PeerPosition.Fix,
        viewMode: MezullaViewMode,
        pilotPosition: Position?,
        now: Instant,
    ): String = when (viewMode) {
        MezullaViewMode.SAFETY -> {
            val altStr = fix.altitudeMeters?.let { "${it}m" } ?: "---"
            val ageSeconds = Duration.between(peer.lastSeenAt, now).seconds
            "$altStr · ${ageSeconds}s ago"
        }

        MezullaViewMode.CLIMB -> {
            val climbStr = peer.climbRateMs?.let {
                val sign = if (it >= 0) "+" else ""
                "$sign${String.format("%.1f", it)} m/s"
            } ?: "--- m/s"
            val altStr = fix.altitudeMeters?.let { "${it}m" } ?: "---"
            "$climbStr · $altStr"
        }

        MezullaViewMode.TACTICAL -> {
            if (pilotPosition == null) {
                "--- · ---"
            } else {
                val peerPos = Position(fix.latitudeDeg, fix.longitudeDeg)
                val distanceKm = pilotPosition.distanceKm(peerPos)
                val bearing = computeBearing(pilotPosition, peerPos)
                val bearingCardinal = degreesToCardinal(bearing)
                val speedKmh = fix.groundSpeedMetersPerSecond?.let {
                    (it * 3.6).roundToInt()
                }
                val speedStr = speedKmh?.let { "$it km/h" } ?: "---"
                "${String.format("%.1f", distanceKm)}km $bearingCardinal · $speedStr"
            }
        }
    }

    // -- Geometry helpers (pure math, no Android dependency) -----------------

    fun computeBearing(from: Position, to: Position): Double {
        val lat1 = Math.toRadians(from.latitudeDeg)
        val lat2 = Math.toRadians(to.latitudeDeg)
        val dLon = Math.toRadians(to.longitudeDeg - from.longitudeDeg)
        val x = sin(dLon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(x, y))
        return (bearing + 360) % 360
    }

    fun degreesToCardinal(degrees: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((degrees + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }
}
