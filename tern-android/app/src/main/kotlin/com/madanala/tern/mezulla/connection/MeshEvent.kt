package com.madanala.tern.mezulla.connection

/**
 * Everything that can come up from a Meshtastic board over the
 * [MeshtasticConnection.events] stream.
 *
 * The taxonomy follows Meshtastic's `PortNum` split — POSITION_APP,
 * TELEMETRY_APP, ALERT_APP — plus link-state transitions that originate on
 * the Tern side. This keeps the wire mapping mechanical: each variant
 * corresponds to exactly one source of truth, so the redux middleware in
 * WS2.4 doesn't have to disambiguate.
 */
sealed interface MeshEvent {

    /**
     * A peer broadcast their position. Maps to a `MeshPacket` on
     * `PortNum.POSITION_APP` with a `Position` payload.
     */
    data class PeerPositionUpdate(
        val peer: PeerIdentity,
        val fix: PeerPosition.Fix,
    ) : MeshEvent

    /**
     * A peer (or our own board, when reflecting back self-telemetry)
     * reported device metrics. Maps to `PortNum.TELEMETRY_APP` with a
     * `DeviceMetrics` payload.
     *
     * Battery level is the only field we surface for now; other metrics
     * (voltage, channel utilization) are not needed by the buddy-flying
     * scenario.
     */
    data class PeerTelemetry(
        val peer: PeerIdentity,
        val batteryPercent: Int?,
        val timestampSeconds: Long,
    ) : MeshEvent

    /**
     * A peer fired an SOS. Maps to `PortNum.ALERT_APP` (port 11). The
     * sender's last known position rides along if they had a fix; if
     * not, [lastKnownPosition] is null and the UI shows "position
     * unknown" — which is itself a known unknown, per the project
     * mission.
     */
    data class PeerAlert(
        val peer: PeerIdentity,
        val lastKnownPosition: PeerPosition.Fix?,
        val timestampSeconds: Long,
    ) : MeshEvent

    /**
     * The link to our paired board transitioned. The same value is also
     * cached at [MeshtasticConnection.linkState], but it is also published
     * here so a single subscriber sees ordering relative to peer events.
     */
    data class LinkStateChange(val newState: LinkState) : MeshEvent

    /**
     * NodeInfo arrived for a peer — typically right after the board
     * downloads its NodeDB to the phone, or when a new peer is heard for
     * the first time. Lets the peer-list UI show a name before any
     * position arrives. Maps to `PortNum.NODEINFO_APP` with a `User`
     * payload.
     */
    data class PeerIdentityKnown(val peer: PeerIdentity) : MeshEvent
}
