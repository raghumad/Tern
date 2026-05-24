package com.madanala.tern.mezulla.ui

import android.util.Log
import com.madanala.tern.mezulla.redux.KnownPeer
import com.madanala.tern.mezulla.redux.PeerState
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.MezullaViewMode
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.ui.overlays.BaseOverlayManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Overlay manager for Mezulla peer markers on the map.
 *
 * One marker per known peer from [PeerState.peers]. Markers are driven
 * entirely by redux state — no spatial queries, no country cache, no
 * weather. When the peer map changes (new peer, updated position, peer
 * goes stale), this manager diffs the current markers against the
 * desired state and adds/removes/updates accordingly.
 *
 * The manager participates in the [OverlayCoordinator] lifecycle the
 * same way as AirspaceOverlayManager and PGSpotOverlayManager: it
 * extends [BaseOverlayManager], registers with the coordinator, and
 * receives redux state changes through [onReduxStateChanged].
 *
 * Staleness is a render-time concern: the marker's opacity and text
 * change based on the age of the peer's last fix, but the peer is
 * never removed from state (that's [PeerState]'s contract).
 */
class MezullaOverlayManager(
    mapStore: MapStore?,
    private val clock: () -> Instant = { Instant.now() },
) : BaseOverlayManager(OverlayType.MEZULLA, mapStore) {

    companion object {
        private const val TAG = "MezullaOverlay"

        // Staleness thresholds (seconds)
        const val FRESH_THRESHOLD_SECONDS = 30L
        const val AGING_THRESHOLD_SECONDS = 120L   // 2 minutes
        const val STALE_THRESHOLD_SECONDS = 300L   // 5 minutes
    }

    // Currently rendered markers, keyed by node number.
    private val renderedPeers = mutableMapOf<Long, PeerMarkerState>()

    data class PeerMarkerState(
        val marker: Marker,
        val peer: KnownPeer,
    )

    override fun onOverlayAttached() {
        // Nothing to do — markers come from redux state, not from spatial queries.
    }

    override fun onOverlayDetached() {
        mapView?.let { map ->
            renderedPeers.values.forEach { pms ->
                map.overlays.remove(pms.marker)
            }
            map.invalidate()
        }
        renderedPeers.clear()
    }

    override fun performMapMove(center: GeoPoint, zoom: Double) {
        // Peer markers don't load data on map move — they come from state.
    }

    override fun onViewportChangedInternal(viewport: BoundingBox) {
        // No viewport-driven loading needed.
    }

    override fun clearOverlays() {
        mapView?.let { map ->
            renderedPeers.values.forEach { pms ->
                map.overlays.remove(pms.marker)
            }
            map.invalidate()
        }
        renderedPeers.clear()
    }

    override fun getRenderedCount(): Int = renderedPeers.size

    override fun reset() {
        renderedPeers.clear()
    }

    /**
     * The main entry point: redux state changed, sync the markers.
     */
    override fun onReduxStateChanged(state: MapState) {
        super.onReduxStateChanged(state)
        syncMarkers(state)
    }

    // -- Marker sync ----------------------------------------------------------

    /**
     * Diff current markers against the desired peer set and update.
     */
    internal fun syncMarkers(state: MapState) {
        val map = mapView ?: return
        val peerState = state.peerState
        val viewMode = state.mezullaViewMode
        val now = clock()
        val userLocation = state.userLocation

        val desiredNodeNumbers = peerState.peers.keys
        val currentNodeNumbers = renderedPeers.keys.toSet()

        // Remove markers for peers that are no longer in state
        val toRemove = currentNodeNumbers - desiredNodeNumbers
        toRemove.forEach { nodeNum ->
            renderedPeers.remove(nodeNum)?.let { pms ->
                map.overlays.remove(pms.marker)
                mOverlayCoordinator?.releaseMarker(pms.marker)
            }
        }

        // Add or update markers for current peers
        for ((nodeNum, peer) in peerState.peers) {
            val fix = peer.lastPosition ?: continue // No position = no marker

            val position = GeoPoint(fix.latitudeDeg, fix.longitudeDeg)
            val staleness = computeStaleness(peer, now)

            val existing = renderedPeers[nodeNum]
            if (existing != null) {
                // Update existing marker
                existing.marker.position = position
                applyMarkerText(existing.marker, peer, viewMode, staleness, userLocation, fix)
                applyMarkerStaleness(existing.marker, staleness)
                applySosPulse(existing.marker, peer, peerState)
                renderedPeers[nodeNum] = existing.copy(peer = peer)
            } else {
                // Create new marker
                val marker = mOverlayCoordinator?.acquireMarker(map) ?: Marker(map)
                marker.position = position
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                applyMarkerText(marker, peer, viewMode, staleness, userLocation, fix)
                applyMarkerStaleness(marker, staleness)
                applySosPulse(marker, peer, peerState)

                map.overlays.add(marker)
                renderedPeers[nodeNum] = PeerMarkerState(marker, peer)
            }
        }

        map.invalidate()
    }

    // -- Staleness ------------------------------------------------------------

    enum class StalenessLevel {
        FRESH,    // < 30s
        AGING,    // 30s - 2min
        STALE,    // 2min - 5min
        LOST,     // 5min+
    }

    internal fun computeStaleness(peer: KnownPeer, now: Instant): StalenessLevel {
        val ageSeconds = Duration.between(peer.lastSeenAt, now).seconds
        return when {
            ageSeconds < FRESH_THRESHOLD_SECONDS -> StalenessLevel.FRESH
            ageSeconds < AGING_THRESHOLD_SECONDS -> StalenessLevel.AGING
            ageSeconds < STALE_THRESHOLD_SECONDS -> StalenessLevel.STALE
            else -> StalenessLevel.LOST
        }
    }

    internal fun applyMarkerStaleness(marker: Marker, staleness: StalenessLevel) {
        marker.alpha = when (staleness) {
            StalenessLevel.FRESH -> 1.0f
            StalenessLevel.AGING -> 0.7f
            StalenessLevel.STALE -> 0.4f
            StalenessLevel.LOST -> 0.25f
        }
    }

    // -- View-mode text -------------------------------------------------------

    internal fun applyMarkerText(
        marker: Marker,
        peer: KnownPeer,
        viewMode: MezullaViewMode,
        staleness: StalenessLevel,
        userLocation: GeoPoint?,
        fix: com.madanala.tern.mezulla.connection.PeerPosition.Fix,
    ) {
        val callsign = peer.identity.longName
            ?: peer.identity.shortName
            ?: peer.identity.hexId

        val secondLine = if (staleness == StalenessLevel.LOST) {
            "lost contact"
        } else {
            formatSecondLine(peer, viewMode, userLocation, fix)
        }

        marker.title = callsign
        marker.snippet = secondLine
    }

    internal fun formatSecondLine(
        peer: KnownPeer,
        viewMode: MezullaViewMode,
        userLocation: GeoPoint?,
        fix: com.madanala.tern.mezulla.connection.PeerPosition.Fix,
    ): String = when (viewMode) {
        MezullaViewMode.SAFETY -> {
            val altStr = fix.altitudeMeters?.let { "${it}m" } ?: "---"
            val ageSeconds = Duration.between(peer.lastSeenAt, clock()).seconds
            "$altStr · ${ageSeconds}s ago"
        }

        MezullaViewMode.CLIMB -> {
            val climbStr = peer.climbRateMs?.let {
                val sign = if (it >= 0) "+" else ""
                "$sign${String.format("%.1f", it)} m/s"
            } ?: "--- m/s"

            // Show absolute altitude from the fix. Relative altitude would
            // require the pilot's own barometric altitude, which GeoPoint
            // doesn't carry. Absolute is the honest answer.
            val altStr = fix.altitudeMeters?.let { "${it}m" } ?: "---"
            "$climbStr · $altStr"
        }

        MezullaViewMode.TACTICAL -> {
            if (userLocation == null) {
                "--- · ---"
            } else {
                val peerPoint = GeoPoint(fix.latitudeDeg, fix.longitudeDeg)
                val distanceKm = userLocation.distanceToAsDouble(peerPoint) / 1000.0
                val bearing = computeBearing(userLocation, peerPoint)
                val bearingCardinal = degreesToCardinal(bearing)
                val speedKmh = fix.groundSpeedMetersPerSecond?.let {
                    (it * 3.6).roundToInt()
                }
                val speedStr = speedKmh?.let { "$it km/h" } ?: "---"
                "${String.format("%.1f", distanceKm)} km $bearingCardinal · $speedStr"
            }
        }
    }

    // -- SOS pulse ------------------------------------------------------------

    private fun applySosPulse(marker: Marker, peer: KnownPeer, peerState: PeerState) {
        val hasSos = peerState.activeAlerts.any {
            it.senderIdentity.nodeNumber == peer.identity.nodeNumber && it.acknowledgedAt == null
        }
        if (hasSos) {
            // SOS peers override staleness: always full opacity, red-ish treatment.
            marker.alpha = 1.0f
        }
    }

    // -- Geometry helpers (no Android dependency) -----------------------------

    internal fun computeBearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val x = sin(dLon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(x, y))
        return (bearing + 360) % 360
    }

    internal fun degreesToCardinal(degrees: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((degrees + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }
}
