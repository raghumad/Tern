package com.madanala.tern.redux

/**
 * Handedness preference for adaptive UI layout
 */
enum class Handedness {
    LEFT_HANDED,
    RIGHT_HANDED,
    AMBIDEXTROUS
}

/**
 * Source of handedness detection for transparency
 */
enum class HandednessSource {
    USER_SELECTED,      // User explicitly chose during onboarding
    SYSTEM_DETECTED,    // Detected from system settings
    SMART_DEFAULT       // Educated guess from device config
}

/**
 * User preferences for adaptive UI
 */
data class UserPreferencesState(
    val handedness: Handedness = Handedness.RIGHT_HANDED,
    val handednessSource: HandednessSource = HandednessSource.SMART_DEFAULT,
    val lastUpdated: Long = System.currentTimeMillis()
)
