package com.madanala.tern.model

import java.time.Instant
import java.util.UUID

/**
 * Simple waypoint model used for Phase 1 MVP route planning.
 */
data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    val type: Type = Type.TURNPOINT,
    val label: String? = null,
    val createdAt: Instant = Instant.now()
) {
    enum class Type { LAUNCH, TURNPOINT, LANDING }
}
