package com.ternparagliding.flight

/**
 * Turns the live [SensorFix] stream into a running wind estimate.
 *
 * The [WindEstimator] is windowed and stateless; this is the thin stateful wrapper the live
 * link needs — it keeps a rolling time-window of recent position fixes and re-fits the
 * circling wind as new fixes arrive. It holds the last good estimate so the deck keeps showing
 * the wind through a straight glide (when no new circle can be fit) rather than blanking;
 * [ageMs] lets the UI fade/flag it once it's old.
 *
 * Not thread-safe — feed it from one collector coroutine. Pure (no Android), so the buffering
 * and "keep last while gliding" behaviour is unit-testable.
 */
class CirclingWindTracker(
    private val windowMs: Long = 35_000L,
    /** Don't re-fit more often than this (a high-rate BLE stream would otherwise refit per byte). */
    private val minRefitGapMs: Long = 1_000L,
) {
    private val buf = ArrayDeque<WindEstimator.TrackSample>()
    private var last: WindEstimator.WindEstimate? = null
    private var lastFixMs: Long = Long.MIN_VALUE
    private var lastFitMs: Long = Long.MIN_VALUE
    private var lastEstimateMs: Long = Long.MIN_VALUE

    /** The most recent wind estimate, or null if none has been resolved yet. */
    val current: WindEstimator.WindEstimate? get() = last

    /**
     * Feed a fix; returns the current best wind estimate (possibly unchanged, possibly null).
     * Fixes without a position (vario before GPS lock) are ignored for wind but don't disturb
     * the buffer.
     */
    fun add(fix: SensorFix): WindEstimator.WindEstimate? {
        val ts = fix.toTrackSample() ?: return last
        lastFixMs = ts.timeMs
        buf.addLast(ts)
        val cutoff = ts.timeMs - windowMs
        while (buf.isNotEmpty() && buf.first().timeMs < cutoff) buf.removeFirst()

        if (lastFitMs == Long.MIN_VALUE || ts.timeMs - lastFitMs >= minRefitGapMs) {
            lastFitMs = ts.timeMs
            WindEstimator.estimateWindow(buf.toList())?.let {
                last = it
                lastEstimateMs = ts.timeMs
            }
        }
        return last
    }

    /** Age of the current estimate at [nowMs] (ms), or null if there is none. */
    fun ageMs(nowMs: Long): Long? = if (last == null) null else nowMs - lastEstimateMs

    fun reset() {
        buf.clear()
        last = null
        lastFixMs = Long.MIN_VALUE
        lastFitMs = Long.MIN_VALUE
        lastEstimateMs = Long.MIN_VALUE
    }
}
