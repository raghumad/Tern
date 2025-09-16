package com.madanala.tern.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel

// Constants for UserDefaults
const val TEMP_UNIT_KEY = "temperature_unit"
const val DIST_UNIT_KEY = "distance_unit"
const val SPEED_UNIT_KEY = "speed_unit"
const val ALT_UNIT_KEY = "altitude_unit"

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("tern_settings_prefs", Context.MODE_PRIVATE)

    var showAirspaces by mutableStateOf(prefs.getBoolean("showAirspaces", true))
        private set
    var showHotspots by mutableStateOf(prefs.getBoolean("showHotspots", true))
        private set
    var showPgSpots by mutableStateOf(prefs.getBoolean("showPgSpots", true))
        private set

    var temperatureUnit by mutableStateOf(prefs.getString(TEMP_UNIT_KEY, "°F") ?: "°F")
        private set
    var distanceUnit by mutableStateOf(prefs.getString(DIST_UNIT_KEY, "km") ?: "km")
        private set
    var speedUnit by mutableStateOf(prefs.getString(SPEED_UNIT_KEY, "kn") ?: "kn")
        private set
    var altitudeUnit by mutableStateOf(prefs.getString(ALT_UNIT_KEY, "ft") ?: "ft")
        private set


    fun onShowAirspacesChanged(show: Boolean) {
        showAirspaces = show
        prefs.edit { putBoolean("showAirspaces", show) }
    }

    fun onShowHotspotsChanged(show: Boolean) {
        showHotspots = show
        prefs.edit { putBoolean("showHotspots", show) }
    }

    fun onShowPgSpotsChanged(show: Boolean) {
        showPgSpots = show
        prefs.edit { putBoolean("showPgSpots", show) }
    }

    fun onTemperatureUnitChanged(unit: String) {
        temperatureUnit = unit
        prefs.edit { putString(TEMP_UNIT_KEY, unit) }
    }

    fun onDistanceUnitChanged(unit: String) {
        distanceUnit = unit
        prefs.edit { putString(DIST_UNIT_KEY, unit) }
    }

    fun onSpeedUnitChanged(unit: String) {
        speedUnit = unit
        prefs.edit { putString(SPEED_UNIT_KEY, unit) }
    }

    fun onAltitudeUnitChanged(unit: String) {
        altitudeUnit = unit
        prefs.edit { putString(ALT_UNIT_KEY, unit) }
    }
}
