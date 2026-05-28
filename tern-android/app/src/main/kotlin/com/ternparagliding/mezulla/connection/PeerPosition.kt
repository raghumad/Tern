package com.ternparagliding.mezulla.connection

/**
 * Position payloads carried over the mesh, in the shape Tern uses
 * internally — already decoded from Meshtastic's `Position` proto.
 *
 * Meshtastic's `Position` uses integer lat/lon scaled by 1e-7 and altitude
 * in meters above MSL. We carry doubles so callers don't keep redoing the
 * scale. [timestampSeconds] is Unix epoch seconds (matches
 * `Position.time`); if the peer never set a timestamp, this is the
 * receive time the board stamped on it.
 */
object PeerPosition {

    /** One peer-reported fix. Fields kept minimal until a real use needs more. */
    data class Fix(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeMeters: Int?,
        val groundSpeedMetersPerSecond: Double?,
        val groundTrackDegrees: Double?,
        val timestampSeconds: Long,
    ) {
        init {
            require(latitudeDeg in -90.0..90.0) {
                "latitude out of range: $latitudeDeg"
            }
            require(longitudeDeg in -180.0..180.0) {
                "longitude out of range: $longitudeDeg"
            }
        }
    }
}
