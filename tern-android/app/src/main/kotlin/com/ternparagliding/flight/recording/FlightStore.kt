package com.ternparagliding.flight.recording

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/**
 * On-disk home for flight recordings (Epic 05 5.2/5.3), crash-survivable by design — the dashcam
 * requirement. Operates on a plain [dir] ([java.io.File]) so it's the same code on device
 * (`context.filesDir/recordings`) and in unit tests (a temp folder).
 *
 * **Two-file scheme per flight:**
 *  - `<id>.live.jsonl` — append-only, one JSON line per record (meta / own fix / peer fix / event),
 *    flushed as it's written. A crash/kill leaves everything up to the last line intact.
 *  - `<id>.flight.json` — the consolidated, sealed [FlightRecording] written on a clean finalise;
 *    the live file is removed once it exists.
 *
 * On startup, [recoverOrphans] turns any `.live.jsonl` with no sealed sibling into a
 * `CRASH_RECOVERED` recording, so a flight is never lost to a mid-air crash of the app.
 *
 * Persistence is Jackson-by-name (the app convention; kept by `proguard-rules.pro`).
 */
class FlightStore(private val dir: File) {

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    init {
        dir.mkdirs()
    }

    /** One line of the live append-only log. Exactly one of the payload fields is set per [kind]. */
    data class LiveLine(
        val kind: Kind,
        val meta: LiveMeta? = null,
        val own: RecordedFix? = null,
        val peer: PeerFixRecord? = null,
        val event: FlightEvent? = null,
    ) {
        enum class Kind { META, OWN, PEER, EVENT }
    }

    data class LiveMeta(
        val id: String,
        val startTimeMs: Long,
        val pilot: String? = null,
        val gliderType: String? = null,
    )

    private fun liveFile(id: String) = dir.resolve("$id.live.jsonl")
    private fun sealedFile(id: String) = dir.resolve("$id.flight.json")

    // --- live (incremental, crash-survivable) ---

    fun beginLive(meta: LiveMeta) = appendLine(meta.id, LiveLine(LiveLine.Kind.META, meta = meta))
    fun appendOwn(id: String, fix: RecordedFix) = appendLine(id, LiveLine(LiveLine.Kind.OWN, own = fix))
    fun appendPeer(id: String, peer: PeerFixRecord) = appendLine(id, LiveLine(LiveLine.Kind.PEER, peer = peer))
    fun appendEvent(id: String, event: FlightEvent) = appendLine(id, LiveLine(LiveLine.Kind.EVENT, event = event))

    private fun appendLine(id: String, line: LiveLine) {
        liveFile(id).appendText(mapper.writeValueAsString(line) + "\n")
    }

    // --- sealed (final) ---

    /** Write the consolidated record and drop its live shadow. */
    fun writeSealed(recording: FlightRecording) {
        sealedFile(recording.id).writeText(mapper.writeValueAsString(recording))
        liveFile(recording.id).delete()
    }

    fun load(id: String): FlightRecording? {
        val f = sealedFile(id)
        if (!f.exists()) return null
        return runCatching { mapper.readValue<FlightRecording>(f) }.getOrNull()
    }

    /** Delete a flight (sealed + any live shadow). Honour [FlightRecording.isProtected] at the call site. */
    fun delete(id: String) {
        sealedFile(id).delete()
        liveFile(id).delete()
    }

    /** All sealed recordings as logbook rows, newest first. Unreadable files are skipped. */
    fun listSummaries(): List<FlightSummary> =
        (dir.listFiles { f -> f.name.endsWith(".flight.json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { mapper.readValue<FlightRecording>(f) }.getOrNull() }
            .map { FlightSummary.from(it) }
            .sortedByDescending { it.startTimeMs }

    /**
     * Recover any live log that never sealed (app died mid-flight) into a `CRASH_RECOVERED`
     * recording, seal it, and return it. Idempotent: a live file with an existing sealed sibling
     * is just cleaned up.
     */
    fun recoverOrphans(): List<FlightRecording> {
        val recovered = ArrayList<FlightRecording>()
        val live = dir.listFiles { f -> f.name.endsWith(".live.jsonl") } ?: return recovered
        for (f in live) {
            val id = f.name.removeSuffix(".live.jsonl")
            if (sealedFile(id).exists()) { f.delete(); continue }
            val rec = runCatching { reconstruct(f) }.getOrNull()
            if (rec != null) {
                writeSealed(rec)
                recovered.add(rec)
            } else {
                f.delete() // unreadable/empty shard — nothing to recover
            }
        }
        return recovered
    }

    /** Rebuild a [FlightRecording] from a live log (used by recovery). */
    private fun reconstruct(file: File): FlightRecording? {
        var meta: LiveMeta? = null
        val own = ArrayList<RecordedFix>()
        val peers = ArrayList<PeerFixRecord>()
        val events = ArrayList<FlightEvent>()
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            val rec = runCatching { mapper.readValue<LiveLine>(line) }.getOrNull() ?: return@forEachLine
            when (rec.kind) {
                LiveLine.Kind.META -> meta = rec.meta
                LiveLine.Kind.OWN -> rec.own?.let { own.add(it) }
                LiveLine.Kind.PEER -> rec.peer?.let { peers.add(it) }
                LiveLine.Kind.EVENT -> rec.event?.let { events.add(it) }
            }
        }
        val m = meta ?: return null
        if (own.isEmpty() && peers.isEmpty()) return null
        val end = maxOf(
            own.maxOfOrNull { it.timeMs } ?: m.startTimeMs,
            peers.maxOfOrNull { it.timeMs } ?: m.startTimeMs,
            events.maxOfOrNull { it.timeMs } ?: m.startTimeMs,
        )
        return FlightRecording(
            id = m.id,
            startTimeMs = m.startTimeMs,
            endTimeMs = end,
            sealReason = SealReason.CRASH_RECOVERED,
            pilot = m.pilot,
            gliderType = m.gliderType,
            ownTrack = own,
            peerTrack = peers,
            events = events,
        )
    }
}
