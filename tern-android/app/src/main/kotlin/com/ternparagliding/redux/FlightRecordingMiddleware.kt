package com.ternparagliding.redux

import android.content.Context
import com.ternparagliding.flight.FlightDetector
import com.ternparagliding.flight.recording.DigestFlightSigner
import com.ternparagliding.flight.recording.FlightRecordingCoordinator
import com.ternparagliding.flight.recording.FlightSigner
import com.ternparagliding.flight.recording.FlightStore
import com.ternparagliding.flight.recording.PeerFixRecord
import com.ternparagliding.mezulla.redux.PeerAction
import com.ternparagliding.spedmo.SpedmoLiveTracker
import com.ternparagliding.spedmo.SpedmoUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Records the flight (Epic 05 5.2). Taps the live streams the rest of the app already produces —
 * `UpdateVarioFix` (own ship) and `PeerPositionReceived` (buddies) — and drives the pure
 * [FlightRecordingCoordinator]: begin on launch, stream every fix to the crash-survivable
 * [FlightStore], seal on landing/SOS.
 *
 * It runs its own [FlightDetector] off the vario fixes (the same airborne logic the follow-cam
 * uses) so it doesn't depend on the camera's UI state. All disk IO is marshalled onto a single
 * ordered IO thread, so the append-only log stays in order and never blocks the main thread at
 * vario rate.
 *
 * Not yet wired (the on-device review items): pilot/glider identity from settings, a manual
 * "end flight" control + logbook UI, "upload to Spedmo" (Epic 03 3.5/3.4), and hardware-Keystore
 * signing (this uses the always-available SHA-256 [DigestFlightSigner] baseline).
 */
class FlightRecordingMiddleware(
    context: Context,
    signer: FlightSigner = DigestFlightSigner(),
) : Middleware {

    private val appContext = context.applicationContext
    private val flightStore = FlightStore(File(appContext.filesDir, "recordings"))
    private val coordinator = FlightRecordingCoordinator(flightStore, signer)

    // Spedmo auto-upload (Epic 03 3.5 / 5.4). Lazy so it's only built (and registers its connectivity
    // drain) once recording actually starts — and only acts when configured + linked + opted in.
    private val uploader by lazy { SpedmoUploader.get(appContext) }

    // Spedmo live tracking (Epic 03 3.4) — pushes position over cell while airborne, gated + throttled.
    private val liveTracker by lazy { SpedmoLiveTracker.get(appContext) }

    // Ordered, single-threaded IO so appends keep their sequence and never run on the main thread.
    private val io = Dispatchers.IO.limitedParallelism(1)

    private var detect = FlightDetector.State()
    private var takeoffDatumM = Double.NaN
    private var recoveredOrphans = false

    override suspend fun process(action: TernAction, store: MapStore) {
        when (action) {
            is MapAction.UpdateVarioFix -> withContext(io) {
                recoverOnce()
                onVarioFix(action.fix)
            }
            is PeerAction.PeerPositionReceived -> withContext(io) {
                if (coordinator.isRecording) coordinator.onPeerFix(action.toPeerFixRecord())
            }
            else -> Unit
        }
    }

    /** One-time crash recovery: seal any live log orphaned by a previous mid-flight kill. */
    private fun recoverOnce() {
        if (recoveredOrphans) return
        recoveredOrphans = true
        // Seal any flight orphaned by a mid-air app kill, and let auto-upload pick them up too.
        runCatching { flightStore.recoverOrphans() }
            .getOrDefault(emptyList())
            .forEach { uploader.onFlightSealed(it.id) }
    }

    private fun onVarioFix(fix: com.ternparagliding.flight.SensorFix) {
        if (fix.hasPosition && takeoffDatumM.isNaN()) fix.gpsAltitudeM?.let { takeoffDatumM = it }
        val heightAboveTakeoff =
            if (takeoffDatumM.isNaN()) null else fix.gpsAltitudeM?.let { it - takeoffDatumM }

        val wasAirborne = detect.airborne
        detect = FlightDetector.update(detect, fix.groundSpeedMs, heightAboveTakeoff)
        if (!wasAirborne && detect.airborne) {
            coordinator.begin(id = "flight-${fix.timeMs}", startTimeMs = fix.timeMs)
        }

        if (coordinator.isRecording) {
            coordinator.onOwnFix(fix, source = "XC_TRACER")
            // Relay position to Spedmo over cell while airborne (gated + throttled inside).
            liveTracker.onAirborneFix(fix)
            // If that fix auto-sealed (landing), hand the flight to Spedmo auto-upload (gated inside)
            // and rearm for a possible next flight this session.
            if (!coordinator.isRecording) {
                coordinator.lastSealed()?.let { uploader.onFlightSealed(it.id) }
                liveTracker.reset()
                detect = FlightDetector.State()
                takeoffDatumM = Double.NaN
            }
        }
    }

    private fun PeerAction.PeerPositionReceived.toPeerFixRecord() = PeerFixRecord(
        peerId = identity.nodeNumber,
        callsign = identity.shortName ?: identity.longName,
        timeMs = fix.timestampSeconds * 1000L,
        lat = fix.latitudeDeg,
        lon = fix.longitudeDeg,
        altitudeM = fix.altitudeMeters,
        groundSpeedMs = fix.groundSpeedMetersPerSecond,
        courseDeg = fix.groundTrackDegrees,
    )
}
