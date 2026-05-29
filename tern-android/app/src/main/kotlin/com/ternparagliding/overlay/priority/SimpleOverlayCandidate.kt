package com.ternparagliding.overlay.priority

/**
 * Concrete [OverlayCandidate] that uses the default scoring formula.
 * Good for overlay types that don't need custom urgency logic, and
 * for tests.
 */
data class SimpleOverlayCandidate(
    override val kind: OverlayKind,
    override val position: Position,
    val id: String,
) : OverlayCandidate
