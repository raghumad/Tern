package com.ternparagliding.claims

import com.ternparagliding.flight.IgcToXctrc
import com.ternparagliding.flight.SensorFix
import com.ternparagliding.flight.XcTracerParser
import com.ternparagliding.flight.recording.DigestFlightSigner
import com.ternparagliding.flight.recording.FlightRecordingCoordinator
import com.ternparagliding.flight.recording.FlightEventType
import com.ternparagliding.flight.recording.FlightStore
import com.ternparagliding.flight.recording.PeerFixRecord
import com.ternparagliding.flight.recording.RecordedFix
import com.ternparagliding.flight.recording.SealReason
import com.ternparagliding.flight.recording.canonicalBytesFor
import com.ternparagliding.sim.igc.IgcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claim-driven tests for **Epic 05 5.2/5.3 — persistence, crash-survival, signing, and the
 * coordinator**. Pilot promise: "the flight is saved, survives the app dying mid-air, is
 * tamper-evident, and shows up in my logbook." The store runs on a real temp [java.io.File] dir,
 * so this is the same code that runs on device against `filesDir`.
 */
class FlightStoreClaimsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = FlightStore(tmp.newFolder("recordings"))

    private fun realFlightFixes(): List<SensorFix> {
        val text = javaClass.getResourceAsStream("/igc/flights/in/2025-10-11-birbilling-richard.igc")!!
            .bufferedReader().use { it.readText() }
        val flight = IgcParser.parseString(text)
        return IgcToXctrc.sentences(flight).mapNotNull { XcTracerParser.parse(it) }
    }

    /**
     * **CLAIM 5.2/5.3 · Resilient (record → seal → logbook → export).** A real flight fed through
     * the coordinator and ended by hand is sealed, signed, persisted, reloadable, listed in the
     * logbook, and exports to a valid IGC. The whole chain on real data.
     */
    @Test
    fun `resilient - a real flight records, seals, persists, and exports`() {
        val store = store()
        val coord = FlightRecordingCoordinator(store, DigestFlightSigner())
        val fixes = realFlightFixes()

        coord.begin(id = "flight-1", startTimeMs = fixes.first().timeMs, pilot = "Richard")
        // Buddy seen early so it's captured regardless of when the flight seals.
        coord.onPeerFix(PeerFixRecord(peerId = 9L, callsign = "BARNEY", timeMs = fixes.first().timeMs + 5000, lat = 32.0, lon = 76.0))
        fixes.forEach { coord.onOwnFix(it, source = "XC_TRACER") }
        coord.endManual()

        val sealed = coord.lastSealed()
        assertNotNull("flight sealed", sealed)
        // A normal XC must NOT incident-seal mid-flight on a spiral-to-land: it records to the end
        // and seals as MANUAL here (or LANDED if the logger ran on stationary > 5 min).
        assertTrue(
            "a real XC doesn't incident-seal (got ${sealed!!.sealReason})",
            sealed.sealReason == SealReason.MANUAL || sealed.sealReason == SealReason.LANDED,
        )
        assertTrue("signed for tamper-evidence", sealed.signature != null)

        // Persisted + reloadable.
        val loaded = store.load("flight-1")
        assertNotNull("sealed flight on disk", loaded)
        assertTrue("substantial capture (whole flight, no early seal)", loaded!!.ownTrack.size > 500)
        assertEquals("buddy captured in the sidecar", 1, loaded.peerTrack.size)
        // The spiral-down-to-land is captured as a bookmark event (not an incident seal), and that
        // keeps the flight from auto-purge.
        assertTrue("rapid descent is bookmarked, not sealed", loaded.events.any { it.type == FlightEventType.RAPID_DESCENT })
        assertTrue("a flight with an incident bookmark is protected", loaded.isProtected)

        // Logbook lists it.
        val summaries = store.listSummaries()
        assertEquals(1, summaries.size)
        assertEquals("flight-1", summaries.first().id)
        assertTrue("a real flight covers real distance", summaries.first().trackDistanceM > 1000.0)

        // Exports to a valid IGC (own-track only).
        val igc = com.ternparagliding.flight.export.IgcWriter.write(
            com.ternparagliding.flight.export.IgcWriter.fromSensorFixes(
                loaded.ownTrack.filter { it.hasPosition }.map {
                    SensorFix(it.timeMs, it.lat, it.lon, it.gpsAltitudeM, it.groundSpeedMs, it.courseDeg, it.climbMs, it.pressureHpa)
                },
            ),
        )
        assertTrue("re-parses as IGC", IgcParser.parseString(igc).fixes.size > 500)
    }

    /**
     * **CLAIM 5.2 · Resilient (crash-survival).** If the app dies mid-flight (never seals), the
     * live append-only log is recovered on next launch into a `CRASH_RECOVERED` recording — the
     * dashcam keeps the footage even when power is cut. The live shadow is consumed once recovered.
     */
    @Test
    fun `resilient - a mid-flight crash recovers from the live log`() {
        val dir = tmp.newFolder("recordings")
        // Simulate a flight in progress: meta + fixes written, but no seal (process dies).
        run {
            val store = FlightStore(dir)
            store.beginLive(FlightStore.LiveMeta("crashed-1", startTimeMs = 1_000L, pilot = "Ariel"))
            store.appendOwn("crashed-1", RecordedFix(timeMs = 1_000L, lat = 46.0, lon = 7.0, gpsAltitudeM = 1500.0))
            store.appendOwn("crashed-1", RecordedFix(timeMs = 2_000L, lat = 46.01, lon = 7.0, gpsAltitudeM = 1520.0))
            store.appendPeer("crashed-1", PeerFixRecord(peerId = 1L, timeMs = 1_500L, lat = 46.2, lon = 7.2))
            // no writeSealed → "crash"
        }

        // Fresh process: recover.
        val store2 = FlightStore(dir)
        val recovered = store2.recoverOrphans()
        assertEquals(1, recovered.size)
        assertEquals(SealReason.CRASH_RECOVERED, recovered.first().sealReason)
        assertEquals("both fixes recovered", 2, recovered.first().ownTrack.size)
        assertEquals("end time is the last record", 2_000L, recovered.first().endTimeMs)

        // It's now a normal sealed flight; recovery is idempotent.
        assertEquals(1, store2.listSummaries().size)
        assertTrue("second recovery finds nothing new", store2.recoverOrphans().isEmpty())
    }

    /**
     * **CLAIM 5.2 · Correct (auto landing seal).** Flying fixes followed by sustained stillness
     * (>5 min, altitude stable) makes the coordinator seal the flight on its own as `LANDED`.
     */
    @Test
    fun `correct - the coordinator auto-seals on a detected landing`() {
        val store = store()
        val coord = FlightRecordingCoordinator(store, DigestFlightSigner())
        coord.begin("auto-1", startTimeMs = 0L)

        // Flying.
        coord.onOwnFix(SensorFix(timeMs = 0L, lat = 46.0, lon = 7.0, gpsAltitudeM = 1500.0, groundSpeedMs = 9.0))
        coord.onOwnFix(SensorFix(timeMs = 60_000L, lat = 46.01, lon = 7.0, gpsAltitudeM = 1300.0, groundSpeedMs = 8.0))
        assertTrue("still flying", coord.isRecording)

        // On the ground, standing still, for > 5 minutes.
        coord.onOwnFix(SensorFix(timeMs = 120_000L, lat = 46.02, lon = 7.0, gpsAltitudeM = 1200.0, groundSpeedMs = 0.2))
        coord.onOwnFix(SensorFix(timeMs = 300_000L, lat = 46.02, lon = 7.0, gpsAltitudeM = 1200.1, groundSpeedMs = 0.1))
        coord.onOwnFix(SensorFix(timeMs = 430_000L, lat = 46.02, lon = 7.0, gpsAltitudeM = 1200.2, groundSpeedMs = 0.1))
        assertFalse("auto-sealed on landing", coord.isRecording)
        assertEquals(SealReason.LANDED, coord.lastSealed()!!.sealReason)
    }

    /**
     * **CLAIM 5.2 · Correct (SOS seal is protected).** An SOS fired in flight seals immediately as
     * `SOS`, records the event, and marks the flight protected (never auto-purged).
     */
    @Test
    fun `correct - SOS seals immediately as protected`() {
        val store = store()
        val coord = FlightRecordingCoordinator(store, DigestFlightSigner())
        coord.begin("sos-1", startTimeMs = 0L)
        coord.onOwnFix(SensorFix(timeMs = 0L, lat = 46.0, lon = 7.0, gpsAltitudeM = 1500.0, groundSpeedMs = 9.0))
        coord.onSosFired(5_000L)

        assertFalse(coord.isRecording)
        val sealed = coord.lastSealed()!!
        assertEquals(SealReason.SOS, sealed.sealReason)
        assertTrue("SOS flight is protected", sealed.isProtected)
        assertTrue("event recorded", sealed.events.any { it.type.name == "SOS_FIRED" })
    }

    /**
     * **CLAIM 5.2 · Correct (tamper-evidence).** The signature covers the canonical content, so an
     * edit to a recorded fix breaks verification — exactly the property the black box needs.
     */
    @Test
    fun `correct - editing a sealed flight breaks the signature`() {
        val store = store()
        val signer = DigestFlightSigner()
        val coord = FlightRecordingCoordinator(store, signer)
        coord.begin("sign-1", startTimeMs = 0L)
        coord.onOwnFix(SensorFix(timeMs = 0L, lat = 46.0, lon = 7.0, gpsAltitudeM = 1500.0, groundSpeedMs = 9.0))
        coord.onOwnFix(SensorFix(timeMs = 1_000L, lat = 46.001, lon = 7.0, gpsAltitudeM = 1490.0, groundSpeedMs = 9.0))
        coord.endManual()

        val sealed = store.load("sign-1")!!
        // Recompute over the untouched record → matches.
        assertEquals(sealed.signature, signer.sign(canonicalBytesFor(sealed)).signature)

        // Tamper: move a fix. Recompute → mismatch.
        val tampered = sealed.copy(
            ownTrack = sealed.ownTrack.mapIndexed { i, f -> if (i == 0) f.copy(lat = 47.0) else f },
        )
        assertFalse(
            "a moved fix no longer matches the signature",
            sealed.signature == signer.sign(canonicalBytesFor(tampered)).signature,
        )
    }
}
