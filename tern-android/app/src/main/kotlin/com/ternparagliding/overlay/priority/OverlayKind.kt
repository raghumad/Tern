package com.ternparagliding.overlay.priority

/**
 * Every overlay type in the system, with a safety weight that
 * determines how aggressively it survives budget cuts. Higher
 * weight = more important = last to be dropped.
 */
enum class OverlayKind(val safetyWeight: Int) {
    SOS_ALERT(1000),
    AIRSPACE(100),
    PEER(80),
    TASK_WAYPOINT(60),
    PG_SPOT(20),
    WEATHER_MARKER(10),
}
