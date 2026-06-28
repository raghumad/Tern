package com.ternparagliding.spedmo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/**
 * Crash- and restart-survivable queue for IGC uploads to Spedmo (Epic 03 3.5 / Epic 05 5.4).
 *
 * Each pending flight is one small JSON file `<flightId>.upload.json` in [dir], so the queue survives
 * process death and reboot — the dashcam/"never lose a flight" requirement. [drain] attempts every
 * still-pending entry once; the caller decides *when* to drain (on finalize and whenever connectivity
 * returns), so there's no timer or wake-lock here.
 *
 * Android-free on purpose (operates on a plain [File] + injected providers) so it unit-tests on the
 * JVM against MockWebServer, exactly like [com.ternparagliding.flight.recording.FlightStore].
 *
 * Retry policy keys off [SpedmoApi]'s classification:
 *  - [SpedmoApi.Result.Transient] (network / 5xx) → stays `QUEUED`, attempts++ (retry next drain),
 *    until [maxAttempts] is hit → `FAILED`.
 *  - [SpedmoApi.Result.AuthError] (4xx — bad key / rejected payload) → `FAILED` immediately (a retry
 *    would just loop).
 *  - [SpedmoApi.Result.Ok] → `UPLOADED`.
 */
class SpedmoUploadQueue(
    private val dir: File,
    private val api: SpedmoApi,
    /** The pilot's member access key, or null if unlinked (drain is a no-op then). */
    private val accessKey: () -> String?,
    /** IGC text for a flight id, or null if it can't be built (no positioned fixes / gone). */
    private val igcFor: (flightId: String) -> String?,
    private val maxAttempts: Int = 8,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    enum class State { QUEUED, UPLOADED, FAILED }

    data class Entry(
        val flightId: String = "",
        val state: State = State.QUEUED,
        val attempts: Int = 0,
        val lastError: String? = null,
        val enqueuedAtMs: Long = 0,
        val updatedAtMs: Long = 0,
    )

    data class DrainResult(val uploaded: Int, val stillQueued: Int, val failed: Int)

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    init {
        dir.mkdirs()
    }

    private fun fileFor(id: String) = dir.resolve("$id.upload.json")

    /** Queue [flightId] for upload (idempotent — a flight already done/queued isn't reset). */
    @Synchronized
    fun enqueue(flightId: String) {
        val existing = read(flightId)
        if (existing != null && existing.state != State.FAILED) return // already queued or uploaded
        val t = now()
        write(Entry(flightId, State.QUEUED, attempts = 0, enqueuedAtMs = t, updatedAtMs = t))
    }

    /** The state of [flightId], or null if it was never queued (i.e. local-only). */
    fun status(flightId: String): State? = read(flightId)?.state

    fun list(): List<Entry> = dir.listFiles { f -> f.name.endsWith(".upload.json") }
        ?.mapNotNull { runCatching { mapper.readValue<Entry>(it) }.getOrNull() }
        ?.sortedByDescending { it.enqueuedAtMs }
        ?: emptyList()

    /** Forget a queue entry (e.g. on flight delete, or to retry a FAILED one via re-enqueue). */
    @Synchronized
    fun remove(flightId: String) {
        fileFor(flightId).delete()
    }

    /**
     * Attempt every `QUEUED` entry once. No-op (all stay queued) when unlinked. Safe to call
     * repeatedly; returns a tally for logging.
     */
    suspend fun drain(): DrainResult {
        val key = accessKey() ?: return DrainResult(0, list().count { it.state == State.QUEUED }, 0)
        var uploaded = 0; var stillQueued = 0; var failed = 0
        for (entry in list()) {
            if (entry.state != State.QUEUED) continue
            val igc = igcFor(entry.flightId)
            if (igc == null) {
                // Nothing to send (no positioned fixes / recording gone) — don't loop forever.
                write(entry.copy(state = State.FAILED, lastError = "no IGC payload", updatedAtMs = now()))
                failed++; continue
            }
            when (val r = api.uploadIgc(key, igc)) {
                is SpedmoApi.Result.Ok -> {
                    write(entry.copy(state = State.UPLOADED, lastError = null, updatedAtMs = now()))
                    uploaded++
                }
                is SpedmoApi.Result.AuthError -> {
                    write(entry.copy(state = State.FAILED, lastError = "HTTP ${r.code}", updatedAtMs = now()))
                    failed++
                }
                is SpedmoApi.Result.Transient -> {
                    val attempts = entry.attempts + 1
                    val state = if (attempts >= maxAttempts) State.FAILED else State.QUEUED
                    write(entry.copy(state = state, attempts = attempts,
                        lastError = "transient ${r.code ?: r.cause?.javaClass?.simpleName ?: "error"}",
                        updatedAtMs = now()))
                    if (state == State.QUEUED) stillQueued++ else failed++
                }
            }
        }
        return DrainResult(uploaded, stillQueued, failed)
    }

    private fun read(id: String): Entry? = fileFor(id).takeIf { it.exists() }
        ?.let { runCatching { mapper.readValue<Entry>(it) }.getOrNull() }

    @Synchronized
    private fun write(entry: Entry) {
        fileFor(entry.flightId).writeText(mapper.writeValueAsString(entry))
    }
}
