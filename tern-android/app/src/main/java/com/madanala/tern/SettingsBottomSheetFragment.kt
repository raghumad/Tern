package com.madanala.tern

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.madanala.tern.databinding.BottomSheetSettingsBinding

class SettingsBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        loadSettings()
        setupListeners()

        binding.buttonExitSettings.setOnClickListener {
            dismiss()
        }
    }

    private fun loadSettings() {
        // Simple Toggles
        binding.switchAirspaces.isChecked = sharedPreferences.getBoolean(PREF_AIRSPACES_ENABLED, true) // Default true as per XML
        binding.switchHotspots.isChecked = sharedPreferences.getBoolean(PREF_HOTSPOTS_ENABLED, false)
        binding.switchPglaunches.isChecked = sharedPreferences.getBoolean(PREF_PGLAUNCHES_ENABLED, false)

        // MaterialButtonToggleGroups - Store the ID of the checked button
        binding.toggleGroupMapView.check(sharedPreferences.getInt(PREF_MAP_VIEW, R.id.button_map_view_terrain))
        binding.toggleGroupTempUnits.check(sharedPreferences.getInt(PREF_TEMP_UNITS, R.id.button_temp_celsius))
        binding.toggleGroupDistUnits.check(sharedPreferences.getInt(PREF_DIST_UNITS, R.id.button_dist_km))
        binding.toggleGroupSpeedUnits.check(sharedPreferences.getInt(PREF_SPEED_UNITS, R.id.button_speed_kmh))
        binding.toggleGroupAltUnits.check(sharedPreferences.getInt(PREF_ALT_UNITS, R.id.button_alt_meters))
    }

    private fun setupListeners() {
        // Simple Toggles
        binding.switchAirspaces.setOnCheckedChangeListener { _, isChecked ->
            saveBooleanSetting(PREF_AIRSPACES_ENABLED, isChecked)
        }
        binding.switchHotspots.setOnCheckedChangeListener { _, isChecked ->
            saveBooleanSetting(PREF_HOTSPOTS_ENABLED, isChecked)
        }
        binding.switchPglaunches.setOnCheckedChangeListener { _, isChecked ->
            saveBooleanSetting(PREF_PGLAUNCHES_ENABLED, isChecked)
        }

        // MaterialButtonToggleGroups
        binding.toggleGroupMapView.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && checkedId != View.NO_ID) saveIntSetting(PREF_MAP_VIEW, checkedId)
        }
        binding.toggleGroupTempUnits.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && checkedId != View.NO_ID) saveIntSetting(PREF_TEMP_UNITS, checkedId)
        }
        binding.toggleGroupDistUnits.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && checkedId != View.NO_ID) saveIntSetting(PREF_DIST_UNITS, checkedId)
        }
        binding.toggleGroupSpeedUnits.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && checkedId != View.NO_ID) saveIntSetting(PREF_SPEED_UNITS, checkedId)
        }
        binding.toggleGroupAltUnits.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && checkedId != View.NO_ID) saveIntSetting(PREF_ALT_UNITS, checkedId)
        }
    }

    private fun saveBooleanSetting(key: String, value: Boolean) {
        with(sharedPreferences.edit()) {
            putBoolean(key, value)
            apply()
        }
    }

    private fun saveIntSetting(key: String, value: Int) {
        with(sharedPreferences.edit()) {
            putInt(key, value)
            apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheetFragment"
        private const val PREFS_NAME = "tern_settings_prefs"

        // Preference Keys for Switches
        private const val PREF_AIRSPACES_ENABLED = "pref_airspaces_enabled"
        private const val PREF_HOTSPOTS_ENABLED = "pref_hotspots_enabled"
        private const val PREF_PGLAUNCHES_ENABLED = "pref_pglaunches_enabled"

        // Preference Keys for MaterialButtonToggleGroups (stores R.id of selected button)
        private const val PREF_MAP_VIEW = "pref_map_view"
        private const val PREF_TEMP_UNITS = "pref_temp_units"
        private const val PREF_DIST_UNITS = "pref_dist_units"
        private const val PREF_SPEED_UNITS = "pref_speed_units"
        private const val PREF_ALT_UNITS = "pref_alt_units"

        fun newInstance(): SettingsBottomSheetFragment {
            return SettingsBottomSheetFragment()
        }
    }
}
