package com.ternparagliding.flight

/**
 * Rolling average of climb over a time window — the "averager" needle pilots center thermals
 * by. The instant vario twitches; the average tells you whether the thermal is actually worth
 * staying in. Stateful, fed from the live `SensorFix.climbMs` stream; pure (no Android) so the
 * windowing is unit-testable.
 */
class ThermalAverager(private val windowMs: Long = 25_000L) {

    private val buf = ArrayDeque<Pair<Long, Double>>() // (timeMs, climbMs)

    /** Add a climb sample; returns the average over the trailing window. */
    fun add(timeMs: Long, climbMs: Double): Double {
        buf.addLast(timeMs to climbMs)
        val cutoff = timeMs - windowMs
        while (buf.isNotEmpty() && buf.first().first < cutoff) buf.removeFirst()
        return buf.sumOf { it.second } / buf.size
    }

    /** Current average, or null if nothing buffered yet. */
    val current: Double? get() = if (buf.isEmpty()) null else buf.sumOf { it.second } / buf.size

    fun reset() = buf.clear()
}
