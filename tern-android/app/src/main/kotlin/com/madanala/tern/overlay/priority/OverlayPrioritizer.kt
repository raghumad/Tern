package com.madanala.tern.overlay.priority

import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified overlay budget enforcer. Scores every candidate, sorts
 * descending, takes the top [budget]. That's it.
 *
 * Thread safety: [budget] is an [AtomicInteger] because
 * `onTrimMemory` on the main thread may halve it while
 * [prioritize] runs on a background thread.
 */
class OverlayPrioritizer(budget: Int = 300) {

    private val _budget = AtomicInteger(budget)

    var budget: Int
        get() = _budget.get()
        set(value) { _budget.set(value) }

    fun prioritize(
        candidates: List<OverlayCandidate>,
        pilotPosition: Position,
    ): List<OverlayCandidate> =
        candidates
            .sortedByDescending { it.score(pilotPosition) }
            .take(budget)
}
