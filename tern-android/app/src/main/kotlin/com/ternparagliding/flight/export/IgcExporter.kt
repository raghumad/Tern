package com.ternparagliding.flight.export

import com.ternparagliding.flight.SensorFix
import com.ternparagliding.flight.recording.FlightRecording

/**
 * Single source of truth for turning a [FlightRecording]'s own-track into IGC text. Used by both the
 * logbook share ([com.ternparagliding.ui.screens.LogbookScreen]) and the Spedmo upload (Epic 03/05
 * 5.4) so the two never diverge. Only the single-pilot own-track is exported — the peer/event sidecar
 * stays local (privacy), per [FlightRecording].
 */
object IgcExporter {

    /** IGC text for [rec], or null if it has no positioned fixes (nothing to export). */
    fun toIgc(rec: FlightRecording): String? {
        val positioned = rec.ownTrack.filter { it.hasPosition }
        if (positioned.isEmpty()) return null
        val flight = IgcWriter.fromSensorFixes(
            positioned.map {
                SensorFix(it.timeMs, it.lat, it.lon, it.gpsAltitudeM, it.groundSpeedMs, it.courseDeg, it.climbMs, it.pressureHpa)
            },
        )
        return IgcWriter.write(flight, IgcWriter.Headers(pilot = rec.pilot ?: "", gliderType = rec.gliderType ?: ""))
    }
}
