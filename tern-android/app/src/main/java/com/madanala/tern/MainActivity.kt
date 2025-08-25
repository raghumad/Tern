package com.madanala.tern

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.madanala.tern.databinding.ActivityMainBinding
import com.madanala.tern.util.getCountryCodeFromLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationService: LocationService
    private lateinit var airspaceService: AirspaceService
    private var airspaceDataLoadedForCountry: String? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationService = LocationService(this)
        airspaceService = AirspaceService(this)

        checkLocationPermissions()

        binding.mapView.onResume()

        lifecycleScope.launch {
            delay(3000)
            binding.welcomeScreenOverlay.visibility = View.GONE
        }

        binding.settingsButton.setOnClickListener {
            Toast.makeText(this, "Settings clicked", Toast.LENGTH_SHORT).show()
        }

        binding.addWaypointButton.setOnClickListener {
            Toast.makeText(this, "Add Waypoint clicked", Toast.LENGTH_SHORT).show()
        }

        binding.shareMenuButton.setOnClickListener { view ->
            showSharePopupMenu(view)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startLocationUpdates()
                } else {
                    Toast.makeText(this, "Location permission required for map functionality", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        locationService.startLocationUpdates { location ->
            binding.mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))

            // Launch a coroutine to call the suspend function
            lifecycleScope.launch {
                val currentCountry = getCountryCodeFromLocation(this@MainActivity, location)
                if (!currentCountry.isNullOrEmpty() && currentCountry != airspaceDataLoadedForCountry) {
                    // loadAirspaceData is already launching its own coroutine, which is fine.
                    loadAirspaceData(currentCountry)
                    airspaceDataLoadedForCountry = currentCountry
                }
            }
        }
    }

    private fun loadAirspaceData(countryCode: String) {
        lifecycleScope.launch {
            try {
                val airspaces = airspaceService.loadAirspaces(countryCode)
                val overlays = airspaceService.createAirspaceOverlays(airspaces)
                binding.mapView.overlays.clear()
                binding.mapView.overlays.addAll(overlays)
                Log.d("MainActivity", "Loaded ${airspaces.size} airspaces for $countryCode")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading airspace data for $countryCode", e)
            }
        }
    }

    private fun showSharePopupMenu(anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        popup.menuInflater.inflate(R.menu.share_options_menu, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_save -> {
                    Toast.makeText(this, "Save selected", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_share_xctsk -> {
                    Toast.makeText(this, "Share .xctsk selected", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_share_qrcode -> {
                    Toast.makeText(this, "Share QRCode selected", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_share_cup -> {
                    Toast.makeText(this, "Share .cup selected", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_share_wpt -> {
                    Toast.makeText(this, "Share .wpt selected", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
