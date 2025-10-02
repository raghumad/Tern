package com.madanala.tern.redux

/**
 * Redux state for overlay management
 */
enum class OverlayType {
    AIRSPACE,
    PG_SPOTS,
    SENSORS,
    TERRAIN
}

data class OverlayConfig(
    val enabled: Boolean = false,
    val opacity: Float = 0.8f,
    val showLabels: Boolean = true,
    val filterRadiusMiles: Double = 300.0
)

data class OverlayState(
    val airspaces: OverlayConfig = OverlayConfig(enabled = true), // Default enabled
    val pgSpots: OverlayConfig = OverlayConfig(enabled = false),  // Disabled by default
    val sensors: OverlayConfig = OverlayConfig(enabled = false),  // Future use
    val terrain: OverlayConfig = OverlayConfig(enabled = false)   // Future use
)
