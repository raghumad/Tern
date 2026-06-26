package com.ternparagliding.flight.recording

import com.ternparagliding.flight.SensorFix

/**
 * The persisted **flight record** — Epic 05's black box. Plain Jackson-by-name DTOs (the app's
 * persistence convention; kept by `proguard-rules.pro`). This is the *full-fidelity* log: every
 * own fix, the buddies around me over time, and the events that shaped the flight — the "flight
 * memory" sidecar. The single-pilot IGC export ([com.ternparagliding.flight.export.IgcWriter]) is
 * derived from [ownTrack]; the peer + event layers stay local (privacy) and power post-flight
 * gaggle replay, incident reconstruction, and swarm-sim fixtures.
 */
data class FlightRecording(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sealReason: SealReason,
    val pilot: String? = null,
    val gliderType: String? = null,
    /** My own positioned/vario fixes, full fidelity, in time order. */
    val ownTrack: List<RecordedFix> = emptyList(),
    /** Buddy positions over the flight (LoRa peers), for gaggle replay. Local-only — never uploaded. */
    val peerTrack: List<PeerFixRecord> = emptyList(),
    /** Cylinder tags, SOS, abnormal descent, manual marks, takeoff/landing. */
    val events: List<FlightEvent> = emptyList(),
    /** Hardware-Keystore signature over the canonical bytes (null until signed). Tamper-evidence. */
    val signature: String? = null,
    /** Key-attestation chain (PEM) proving the signature came from secure hardware, when available. */
    val attestationPem: String? = null,
    /** Server counter-signature + RFC-3161 timestamp token, once uploaded (the authoritative tier). */
    val serverTimestampToken: String? = null,
) {
    val durationMs: Long get() = endTimeMs - startTimeMs

    /** True once an incident sealed it (SOS / rapid descent / crash-recovered) — never auto-purge. */
    val isProtected: Boolean
        get() = sealReason == SealReason.SOS ||
            sealReason == SealReason.RAPID_DESCENT ||
            sealReason == SealReason.CRASH_RECOVERED
}

/**
 * One own-ship fix, mirroring [SensorFix] (decoupled so the on-disk schema is stable independent
 * of the sensor type). `source` records which sensor was live (the K7 faithful-replay field).
 */
data class RecordedFix(
    val timeMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val gpsAltitudeM: Double? = null,
    val groundSpeedMs: Double? = null,
    val courseDeg: Double? = null,
    val climbMs: Double? = null,
    val pressureHpa: Double? = null,
    val source: String? = null,
) {
    val hasPosition: Boolean get() = lat != null && lon != null
}

/** A buddy position at a moment in the flight. Decoupled from the mezulla wire type for stable storage. */
data class PeerFixRecord(
    val peerId: Long,
    val callsign: String? = null,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Int? = null,
    val groundSpeedMs: Double? = null,
    val courseDeg: Double? = null,
    val climbMs: Double? = null,
)

/** A point-in-time event worth marking in the record. */
data class FlightEvent(
    val timeMs: Long,
    val type: FlightEventType,
    val detail: String? = null,
)

enum class FlightEventType {
    TAKEOFF,
    LANDING,
    CYLINDER_TAG,
    SOS_FIRED,
    SOS_RECEIVED,
    RAPID_DESCENT,
    MANUAL_MARK,
}

/** Why the recording stopped — distinguishes a normal landing from an incident seal. */
enum class SealReason {
    /** Auto-detected landing (ground speed ≈ 0 + altitude stable). */
    LANDED,
    /** Pilot ended the flight by hand. */
    MANUAL,
    /** SOS fired — sealed as evidence. */
    SOS,
    /** Sustained abnormal descent detected — sealed as evidence. */
    RAPID_DESCENT,
    /** Recovered from an incremental file after a crash/kill (never cleanly finalised). */
    CRASH_RECOVERED,
}

/** Map a live [SensorFix] to a stored [RecordedFix], stamping the live sensor [source]. */
fun SensorFix.toRecordedFix(source: String? = null): RecordedFix = RecordedFix(
    timeMs = timeMs,
    lat = lat,
    lon = lon,
    gpsAltitudeM = gpsAltitudeM,
    groundSpeedMs = groundSpeedMs,
    courseDeg = courseDeg,
    climbMs = climbMs,
    pressureHpa = pressureHpa,
    source = source,
)
