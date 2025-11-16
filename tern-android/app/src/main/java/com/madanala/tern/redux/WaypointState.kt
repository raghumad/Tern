package com.madanala.tern.redux

/**
 * Waypoint selection state for interactive editing
 */
data class WaypointSelection(
    val routeId: String,
    val waypointId: String,
    val isDragging: Boolean = false,
    val originalLat: Double? = null,
    val originalLon: Double? = null
)
