package com.ternparagliding.flight

import com.ternparagliding.sim.igc.IgcFlight
import com.ternparagliding.sim.igc.IgcParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a recorded IGC flight as a live [SensorFix] stream — the **own-ship** analogue of
 * [com.ternparagliding.sim.injector.VirtualPeerInjector] (which replays IGC as peer frames).
 * It exists so the flight deck can be flown on the bench: a real Bir Billing / Aravis flight
 * drives the *same* path a connected XC Tracer would (`$XCTRC → XcTracerParser → SensorFix`),
 * so the HUD, wind brain, track, and camera all run their production code against real data.
 *
 * Fidelity is identical to [IgcToXctrc] (coarse 1 Hz baro-derived climb, gap honesty); the only
 * thing added here is *timing* — fixes are emitted at flight cadence divided by [speedMultiplier],
 * with each inter-fix wait clamped so a logger gap doesn't stall the replay.
 */
class IgcReplaySource(
    private val flight: IgcFlight,
    private val speedMultiplier: Int = DEFAULT_SPEED,
) {

    /** Emits one [SensorFix] per valid fix, paced to [speedMultiplier]× real time. */
    fun fixes(): Flow<SensorFix> = flow {
        val parsed = IgcToXctrc.sentences(flight).mapNotNull { XcTracerParser.parse(it) }
        for (i in parsed.indices) {
            emit(parsed[i])
            if (i + 1 < parsed.size) {
                val dtMs = parsed[i + 1].timeMs - parsed[i].timeMs
                val waitMs = (dtMs / speedMultiplier).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
                delay(waitMs)
            }
        }
    }

    /**
     * Emits fixes on a SHARED wall-clock timeline so multiple devices each replaying a different
     * pilot of the same session fly the *true simultaneous gaggle*. Each fix is scheduled at
     * `anchorWallMs + (fix.timeMs − sessionStartMs) / speedMultiplier`: every device uses the same
     * [anchorWallMs] (a near-future wall instant both agree on) and [sessionStartMs] (the session's
     * shared flight epoch), so absolute flight time maps identically to wall time on both — the
     * pilots stay aligned. Fixes before [sessionStartMs], or ones we've already fallen behind on,
     * are skipped so a device that joins late jumps straight to the live moment rather than racing to
     * catch up (which would briefly mis-time climb). Runs at 1× by default for faithful climb.
     */
    fun fixesSynced(anchorWallMs: Long, sessionStartMs: Long): Flow<SensorFix> = flow {
        val parsed = IgcToXctrc.sentences(flight).mapNotNull { XcTracerParser.parse(it) }
        for (fix in parsed) {
            if (fix.timeMs < sessionStartMs) continue // before the shared start → not airborne yet
            val scheduledWall = anchorWallMs + (fix.timeMs - sessionStartMs) / speedMultiplier
            val waitMs = scheduledWall - System.currentTimeMillis()
            when {
                waitMs > 0 -> delay(waitMs)
                waitMs < -SKIP_BEHIND_MS -> continue // fell behind (slow LoRa / late tap) → stay aligned
            }
            emit(fix)
        }
    }

    companion object {
        /** 1 minute of flight ≈ 7.5 s on the bench — fast enough to scrub, slow enough to read. */
        const val DEFAULT_SPEED = 8

        private const val MIN_STEP_MS = 40L   // don't flood the UI faster than ~25 fps
        private const val MAX_STEP_MS = 1_000L // a logger gap shouldn't freeze the replay

        /** In a synced replay, skip a fix we're more than this far behind on (stay aligned, don't race). */
        private const val SKIP_BEHIND_MS = 2_000L

        /** The Bir Billing pilots, by replay id (own-ship for the two-device buddy test). */
        val BIRBILLING_PILOTS = listOf("birbilling-richard", "birbilling-barney", "birbilling-ariel")

        /** First parsed-fix epoch (ms) of a bundled flight, or null. Same pipeline as the replay. */
        fun firstFixEpochMs(flightId: String): Long? {
            val flight = load(flightId) ?: return null
            return IgcToXctrc.sentences(flight).mapNotNull { XcTracerParser.parse(it) }.firstOrNull()?.timeMs
        }

        /**
         * The shared sync epoch for the Bir Billing session: the LATEST of the pilots' first fixes,
         * so every pilot has data from the start (no one stares at a static screen waiting for a
         * late launcher). Deterministic → both devices compute the same value with no comms.
         */
        fun birBillingSyncStartMs(): Long? =
            BIRBILLING_PILOTS.mapNotNull { firstFixEpochMs(it) }.maxOrNull()

        /**
         * Bundled demo flights, by id (the resource lives in `src/main/resources`, packaged into
         * the APK and read off the classpath at runtime — same mechanism the Aravis demo uses).
         */
        val FLIGHTS: Map<String, String> = mapOf(
            "birbilling" to "/igc/flights/in/2025-10-11-birbilling-richard.igc",
            // Own-ship = tonio24 (the scenario DUT) so it doesn't collide with a buddy peer.
            "aravis" to "/igc/flights/fr/2026-04-25-aravis-team-tonio24.igc",
            // Per-pilot Bir Billing tracks for the two-device over-LoRa buddy test: each phone
            // replays one pilot and broadcasts it, so the other phone sees a real moving buddy.
            "birbilling-richard" to "/igc/flights/in/2025-10-11-birbilling-richard.igc",
            "birbilling-barney" to "/igc/flights/in/2025-10-11-birbilling-barney.igc",
            "birbilling-ariel" to "/igc/flights/in/2025-10-11-birbilling-ariel.igc",
        )

        /** Load a bundled flight by id, or null if unknown / unreadable. */
        fun load(flightId: String): IgcFlight? {
            val path = FLIGHTS[flightId] ?: return null
            val text = IgcReplaySource::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() } ?: return null
            return runCatching { IgcParser.parseString(text) }.getOrNull()
        }
    }
}
