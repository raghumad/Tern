package com.ternparagliding.sim.swarm

import java.time.LocalDate

/**
 * A multi-pilot flight scenario the swarm simulator can play back.
 *
 * The data lives as a Kotlin data class for now — see [ScenarioLoader].
 * A YAML representation could replace this later without any caller
 * change; the data class is the contract.
 *
 * @property name short stable slug, e.g. "aravis-team-2026-04-25".
 *   Used in test output and as a directory name when scenarios grow
 *   companion files (annotations, golden tracks, etc.).
 * @property location free-text site name (e.g. "Col de la Forclaz,
 *   Aravis, France"). Pilot-readable, not parsed.
 * @property date the calendar date the flights happened. Useful when
 *   the test wants to assert "we're replaying real history."
 * @property region two-letter ISO country code of the launch site,
 *   matching the directory under `flights/<region>/`.
 * @property pilots one entry per pilot taking part. Order is
 *   meaningful for tests that pick "pilot 0" by convention; otherwise
 *   addressed by [ScenarioPilot.id].
 * @property notes human prose explaining what this scenario exercises
 *   — terrain, launch staggering, team dynamics, expected anomalies.
 *   Read by humans, not asserted on.
 */
data class Scenario(
    val name: String,
    val location: String,
    val date: LocalDate,
    val region: String,
    val pilots: List<ScenarioPilot>,
    val notes: String,
) {
    init {
        require(pilots.isNotEmpty()) { "scenario must have at least one pilot" }
        val ids = pilots.map { it.id }
        require(ids.toSet().size == ids.size) {
            "duplicate pilot ids in scenario '$name': $ids"
        }
    }

    fun pilot(id: PilotId): ScenarioPilot? = pilots.firstOrNull { it.id == id }
}

/**
 * One pilot in a [Scenario]. The IGC is looked up on the test
 * classpath at [igcResourcePath] (e.g.
 * `/igc/flights/fr/2026-04-25-aravis-team-tonio24.igc`).
 *
 * @property id short, stable, machine handle (often the pilot's
 *   XContest username). Used to address the pilot from tests and BDD
 *   scenarios.
 * @property displayName human-friendly name for UI / screenshots.
 *   Empty string is allowed when the pilot wants to be addressed by
 *   handle alone.
 * @property igcResourcePath absolute classpath resource path (starts
 *   with `/`) of the IGC file. Kept as a string rather than a `File`
 *   so the scenario stays valid across packaging changes.
 * @property wing optional wing description (model + size + class).
 *   Free text; useful context for debugging glide / sink rate
 *   anomalies during playback.
 * @property notes pilot-specific anomalies the test reader might want
 *   to know about ("launched 22 min late from a different site").
 */
data class ScenarioPilot(
    val id: PilotId,
    val displayName: String,
    val igcResourcePath: String,
    val wing: String = "",
    val notes: String = "",
)

/**
 * Pilot handle. A typed wrapper around String so we never confuse a
 * pilot id with a display name, file path, or arbitrary string in
 * method signatures.
 */
@JvmInline
value class PilotId(val value: String) {
    init {
        require(value.isNotBlank()) { "pilot id must not be blank" }
    }

    override fun toString(): String = value
}
