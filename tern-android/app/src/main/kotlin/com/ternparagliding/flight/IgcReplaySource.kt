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

    companion object {
        /** 1 minute of flight ≈ 7.5 s on the bench — fast enough to scrub, slow enough to read. */
        const val DEFAULT_SPEED = 8

        private const val MIN_STEP_MS = 40L   // don't flood the UI faster than ~25 fps
        private const val MAX_STEP_MS = 1_000L // a logger gap shouldn't freeze the replay

        /**
         * Bundled demo flights, by id (the resource lives in `src/main/resources`, packaged into
         * the APK and read off the classpath at runtime — same mechanism the Aravis demo uses).
         */
        val FLIGHTS: Map<String, String> = mapOf(
            "birbilling" to "/igc/flights/in/2025-10-11-birbilling-richard.igc",
            "aravis" to "/igc/flights/fr/2026-04-25-aravis-team-cbe.igc",
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
