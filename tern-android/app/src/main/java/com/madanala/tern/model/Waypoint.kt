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
    val createdAt: Instant = Instant.now(),
    val routeId: String? = null,
    val radius: Double? = 400.0, // Default FAI cylinder radius in meters
    val alt: Double? = null, // Altitude in meters
    val openTime: String? = null, // HH:mm
    val closeTime: String? = null // HH:mm
) {
    enum class Type { LAUNCH, TURNPOINT, SSS, ESS, GOAL, LANDING }
}
