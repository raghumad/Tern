package com.ternparagliding.flight.recording

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * At-a-glance stats for a [FlightRecording] — the logbook row (Epic 05 5.3). Pure derivation from
 * the recorded own-track, so it's claim-testable and the logbook never stores anything it can't
 * recompute. Null where the inputs don't support it (the K7 honesty rule — no faked numbers).
 */
data class FlightSummary(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val sealReason: SealReason,
    val isProtected: Boolean,
    val fixCount: Int,
    val maxAltitudeM: Double?,
    val minAltitudeM: Double?,
    val maxClimbMs: Double?,
    val maxSinkMs: Double?,
    /** Cumulative along-track distance (m) over positioned fixes. */
    val trackDistanceM: Double,
    /** Straight-line takeoff→landing distance (m). */
    val straightDistanceM: Double,
    val takeoff: LatLon?,
    val landing: LatLon?,
    val peerCount: Int,
) {
    companion object {
        private const val EARTH_R_M = 6_371_000.0

        fun from(r: FlightRecording): FlightSummary {
            val positioned = r.ownTrack.filter { it.hasPosition }
            val alts = r.ownTrack.mapNotNull { it.gpsAltitudeM }
            val climbs = r.ownTrack.mapNotNull { it.climbMs }

            var dist = 0.0
            for (i in 1 until positioned.size) {
                val a = positioned[i - 1]
                val b = positioned[i]
                dist += haversineM(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
            }

            val takeoff = positioned.firstOrNull()?.let { LatLon(it.lat!!, it.lon!!) }
            val landing = positioned.lastOrNull()?.let { LatLon(it.lat!!, it.lon!!) }
            val straight = if (takeoff != null && landing != null) {
                haversineM(takeoff.lat, takeoff.lon, landing.lat, landing.lon)
            } else 0.0

            return FlightSummary(
                id = r.id,
                startTimeMs = r.startTimeMs,
                endTimeMs = r.endTimeMs,
                durationMs = r.durationMs,
                sealReason = r.sealReason,
                isProtected = r.isProtected,
                fixCount = r.ownTrack.size,
                maxAltitudeM = alts.maxOrNull(),
                minAltitudeM = alts.minOrNull(),
                maxClimbMs = climbs.maxOrNull(),
                maxSinkMs = climbs.minOrNull(),
                trackDistanceM = dist,
                straightDistanceM = straight,
                takeoff = takeoff,
                landing = landing,
                peerCount = r.peerTrack.map { it.peerId }.distinct().size,
            )
        }

        /** Great-circle distance in metres. */
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return EARTH_R_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

/** A plain coordinate pair (decoupled from osmdroid/maplibre geometry types). */
data class LatLon(val lat: Double, val lon: Double)
