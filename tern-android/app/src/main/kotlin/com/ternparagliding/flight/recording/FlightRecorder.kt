package com.ternparagliding.flight.recording

import com.ternparagliding.flight.SensorFix

/**
 * The in-flight accumulator that becomes a [FlightRecording] when sealed. Pure (no IO, no Android)
 * so the capture rules are unit-testable; the disk/persistence seam lives in the Android wiring,
 * which feeds this from the raw fused-fix stream.
 *
 * Crucially this is a **sibling off the raw fix stream, not chained to `FlightTrack`'s eviction**:
 * the ring buffer decimates at 5 m for rendering, which would throw away the slow-moving fixes that
 * show a thermal climb. The recorder keeps **every** fix at full cadence — IGC / XContest / Spedmo
 * want time-based ~1 Hz, not spatial thinning.
 */
class FlightRecorder(
    val id: String,
    val startTimeMs: Long,
    private val pilot: String? = null,
    private val gliderType: String? = null,
) {
    private val own = ArrayList<RecordedFix>()
    private val peers = ArrayList<PeerFixRecord>()
    private val events = ArrayList<FlightEvent>()
    private var lastTimeMs = startTimeMs
    private var sealed: FlightRecording? = null

    val ownFixCount: Int get() = own.size
    val peerFixCount: Int get() = peers.size
    val eventCount: Int get() = events.size
    val isSealed: Boolean get() = sealed != null

    /** The latest fix time seen — the natural end time if the caller doesn't supply one. */
    val lastFixTimeMs: Long get() = lastTimeMs

    /** Record one own-ship fix. [source] names the live sensor (e.g. "XC_TRACER", "PHONE"). */
    fun addOwnFix(fix: SensorFix, source: String? = null) {
        check(sealed == null) { "recording $id is already sealed" }
        own.add(fix.toRecordedFix(source))
        if (fix.timeMs > lastTimeMs) lastTimeMs = fix.timeMs
    }

    /** Record one buddy position. */
    fun addPeerFix(rec: PeerFixRecord) {
        check(sealed == null) { "recording $id is already sealed" }
        peers.add(rec)
        if (rec.timeMs > lastTimeMs) lastTimeMs = rec.timeMs
    }

    /** Mark an event (cylinder tag, SOS, abnormal descent, manual mark, …). */
    fun addEvent(event: FlightEvent) {
        check(sealed == null) { "recording $id is already sealed" }
        events.add(event)
        if (event.timeMs > lastTimeMs) lastTimeMs = event.timeMs
    }

    /**
     * Finalise into an immutable [FlightRecording]. Idempotent: a second seal returns the first
     * result (so a landing-seal that races a manual-seal can't double-finalise). [endTimeMs]
     * defaults to the last fix/event time; [signature]/[attestationPem] attach tamper-evidence.
     */
    fun seal(
        reason: SealReason,
        endTimeMs: Long = lastTimeMs,
        signature: String? = null,
        attestationPem: String? = null,
    ): FlightRecording {
        sealed?.let { return it }
        val rec = FlightRecording(
            id = id,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            sealReason = reason,
            pilot = pilot,
            gliderType = gliderType,
            ownTrack = own.toList(),
            peerTrack = peers.toList(),
            events = events.toList(),
            signature = signature,
            attestationPem = attestationPem,
        )
        sealed = rec
        return rec
    }
}
