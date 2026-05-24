package com.madanala.tern.sim.swarm

import java.time.Duration
import java.time.Instant

/**
 * Multi-pilot, time-aligned IGC playback engine (WS1.2).
 *
 * Given a [Scenario], parses every pilot's IGC eagerly at construction
 * time, then answers "where was pilot X at time T?" with linear
 * interpolation between adjacent fixes. A separate iterator advances
 * a single global clock and exposes everyone's position at each tick.
 *
 * Design notes:
 *  - Parses everything in the constructor. The whole scenario gets
 *    loaded once and queried many times; deferring parse to first
 *    query would make the first query mysteriously slow.
 *  - Position queries do binary search per call (see
 *    [PilotState.positionAt]). Pre-expanding to per-second arrays
 *    would be ~40k entries per pilot per second of resolution, which
 *    is wasteful when most consumers only sample at coarse ticks.
 *  - The global clock iterator is a [Sequence], not a `List`. It
 *    streams; nothing materialises until consumed. Iteration window
 *    covers `[earliest first fix .. latest last fix]` of the whole
 *    scenario so pilots who launch late simply return null until
 *    their own first fix.
 */
class SwarmPlayback(
    val scenario: Scenario,
    val tickInterval: Duration = Duration.ofSeconds(1),
    flightsOverride: Map<PilotId, com.madanala.tern.sim.igc.IgcFlight>? = null,
) {
    init {
        require(!tickInterval.isZero && !tickInterval.isNegative) {
            "tickInterval must be > 0 (got $tickInterval)"
        }
    }

    private val states: Map<PilotId, PilotState> = buildStates(flightsOverride)

    /** Pilot handles in the scenario's declared order. */
    val pilots: List<PilotId> = scenario.pilots.map { it.id }

    /** Earliest first-fix time across all pilots. */
    val swarmStart: Instant = states.values.minOf { it.firstFixTime }

    /** Latest last-fix time across all pilots. */
    val swarmEnd: Instant = states.values.maxOf { it.lastFixTime }

    /** Per-pilot state, addressable by [PilotId]. */
    fun pilot(id: PilotId): PilotState =
        states[id] ?: error("unknown pilot id '$id' (scenario: '${scenario.name}')")

    /**
     * Position of [pilotId] at [atTime], or null if [atTime] lies
     * outside that pilot's first..last fix range (pre-launch or
     * post-landing).
     */
    fun currentPosition(pilotId: PilotId, atTime: Instant): PilotPosition? =
        pilot(pilotId).positionAt(atTime)

    /**
     * Lazy sequence of clock ticks from [from] (inclusive) to [until]
     * (inclusive) at [tickInterval]. Each tick yields one
     * [SwarmTick] containing every pilot's position at that instant
     * (or null if they're not yet launched / already landed).
     *
     * Defaults span the full scenario, so the natural use is
     * `playback.ticks().forEach { ... }`. The iterator stops when
     * [until] is passed — by then every pilot has landed, by
     * definition of [swarmEnd].
     */
    fun ticks(from: Instant = swarmStart, until: Instant = swarmEnd): Sequence<SwarmTick> {
        require(!until.isBefore(from)) {
            "ticks(from=$from, until=$until): until must not precede from"
        }
        return generateSequence(from) { prev ->
            val next = prev.plus(tickInterval)
            if (next.isAfter(until)) null else next
        }.map { instant ->
            val positions = LinkedHashMap<PilotId, PilotPosition?>(pilots.size)
            for (id in pilots) {
                positions[id] = states.getValue(id).positionAt(instant)
            }
            SwarmTick(instant, positions)
        }
    }

    private fun buildStates(
        flightsOverride: Map<PilotId, com.madanala.tern.sim.igc.IgcFlight>?,
    ): Map<PilotId, PilotState> {
        val flights = flightsOverride ?: ScenarioLoader.load(scenario)
        val missing = scenario.pilots.map { it.id }.filter { it !in flights }
        require(missing.isEmpty()) {
            "no flight loaded for pilots: $missing"
        }
        val out = LinkedHashMap<PilotId, PilotState>(scenario.pilots.size)
        for (pilot in scenario.pilots) {
            val flight = flights.getValue(pilot.id)
            out[pilot.id] = PilotState(pilot.id, pilot, flight)
        }
        return out
    }
}

/**
 * One tick of the global clock.
 *
 * @property time the wall-clock instant this tick represents.
 * @property positions one entry per pilot in the scenario, in
 *   scenario-declared order. Value is null for pilots whose recorded
 *   flight hasn't started yet (or has already ended) at [time].
 */
data class SwarmTick(
    val time: Instant,
    val positions: Map<PilotId, PilotPosition?>,
)
