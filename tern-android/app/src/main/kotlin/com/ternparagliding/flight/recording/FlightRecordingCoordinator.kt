package com.ternparagliding.flight.recording

import com.ternparagliding.flight.SensorFix

/**
 * Drives one flight's recording lifecycle (Epic 05 5.2): begin on launch, stream every fix to the
 * in-memory [FlightRecorder] *and* the crash-survivable [FlightStore] live log, run the landing /
 * abnormal-end detectors, and seal (sign + consolidate) when the flight ends. Deps-free of Android
 * — the redux middleware is a thin adapter that feeds this from the live streams; this class holds
 * the actual logic and is unit-tested end-to-end against a replayed flight.
 */
class FlightRecordingCoordinator(
    private val store: FlightStore,
    private val signer: FlightSigner = DigestFlightSigner(),
) {
    private var recorder: FlightRecorder? = null
    private var landing = LandingDetector.State()
    private var abnormal = AbnormalEndDetector.State()
    private var lastSealed: FlightRecording? = null

    val isRecording: Boolean get() = recorder != null
    val activeId: String? get() = recorder?.id

    /** Begin recording (call once the [com.ternparagliding.flight.FlightDetector] latches airborne). */
    fun begin(id: String, startTimeMs: Long, pilot: String? = null, gliderType: String? = null) {
        if (recorder != null) return
        recorder = FlightRecorder(id, startTimeMs, pilot, gliderType)
        landing = LandingDetector.State()
        abnormal = AbnormalEndDetector.State()
        lastSealed = null
        store.beginLive(FlightStore.LiveMeta(id, startTimeMs, pilot, gliderType))
    }

    /**
     * Feed one own-ship fix. A sustained rapid descent is **bookmarked** as an event (so the black
     * box captures the moment) but does NOT seal — a spiral-to-land is a normal rapid descent, not
     * an incident, and stopping a good flight's recording would be the real bug. The only automatic
     * seal is a detected landing; the incident seal is an explicit SOS ([onSosFired]).
     */
    fun onOwnFix(fix: SensorFix, source: String? = null) {
        val r = recorder ?: return
        r.addOwnFix(fix, source)
        store.appendOwn(r.id, fix.toRecordedFix(source))

        val wasFlagged = abnormal.flagged
        abnormal = AbnormalEndDetector.update(abnormal, fix.climbMs)
        if (abnormal.flagged && !wasFlagged) {
            // Bookmark once (the detector latches) — marks the moment for review, keeps recording.
            onEvent(FlightEvent(fix.timeMs, FlightEventType.RAPID_DESCENT))
        }

        landing = LandingDetector.update(landing, fix.timeMs, fix.groundSpeedMs, fix.gpsAltitudeM)
        if (landing.landed) seal(SealReason.LANDED)
    }

    /** Feed one buddy position (LoRa peer) into the flight-memory sidecar. */
    fun onPeerFix(peer: PeerFixRecord) {
        val r = recorder ?: return
        r.addPeerFix(peer)
        store.appendPeer(r.id, peer)
    }

    /** Mark an event (cylinder tag, manual mark, …). */
    fun onEvent(event: FlightEvent) {
        val r = recorder ?: return
        r.addEvent(event)
        store.appendEvent(r.id, event)
    }

    /** SOS fired in flight — record it and seal the black box as protected evidence. */
    fun onSosFired(timeMs: Long) {
        recorder ?: return
        onEvent(FlightEvent(timeMs, FlightEventType.SOS_FIRED))
        seal(SealReason.SOS)
    }

    /** Pilot ended the flight by hand (the fallback when auto-landing doesn't fire). */
    fun endManual() {
        if (recorder != null) seal(SealReason.MANUAL)
    }

    /** The most recently sealed recording (e.g. for "upload to Spedmo" or a logbook jump). */
    fun lastSealed(): FlightRecording? = lastSealed

    private fun seal(reason: SealReason) {
        val r = recorder ?: return
        val draft = r.seal(reason)
        val signed = signer.sign(canonicalBytesFor(draft))
        val finalRec = if (signed != null) {
            draft.copy(signature = signed.signature, attestationPem = signed.attestationPem)
        } else {
            draft
        }
        store.writeSealed(finalRec)
        lastSealed = finalRec
        recorder = null
    }
}
