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
        private const val WELCOME_SCREEN_DURATION_MS = 2000L // 2 seconds
        private const val WELCOME_SCREEN_FADE_MS = 500L  // 0.5 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 3. Full screen app
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize services
        locationService = LocationService(this)
        airspaceService = AirspaceService(this)

        // 2. Welcome Screen: Fade out and disappear
        if (savedInstanceState == null) { // Only show welcome screen on first create
            lifecycleScope.launch {
                delay(WELCOME_SCREEN_DURATION_MS)
                binding.welcomeScreenOverlay.animate()
                    .alpha(0f)
                    .setDuration(WELCOME_SCREEN_FADE_MS)
                    .withEndAction {
                        binding.welcomeScreenOverlay.visibility = View.GONE
                    }
                    .start()
            }
        } else {
            // If activity is recreated (e.g. orientation change), hide welcome screen immediately
             binding.welcomeScreenOverlay.visibility = View.GONE
        }


        // MapView setup (ensure permissions are checked before using map features)
        binding.mapView.onResume() // Call onResume for map here if needed, or in onResume()

        checkLocationPermissions() // This will also call startLocationUpdates if permission granted

        // 5. Settings Button Click Listener
        binding.settingsButton.setOnClickListener {
            SettingsBottomSheetFragment.newInstance().show(supportFragmentManager, SettingsBottomSheetFragment.TAG)
        }

        // 6. Bottom Controls Click Listeners
        binding.addWaypointButton.setOnClickListener {
            Toast.makeText(this, "Add Waypoint button clicked", Toast.LENGTH_SHORT).show()
            // TODO: Implement add waypoint functionality
        }

        binding.shareMenuButton.setOnClickListener { view ->
            showSharePopupMenu(view)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        // If location updates were stopped in onPause, restart them here
        // after checking permissions again, or if already granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
             // Potentially re-start location updates if they are not continuous
             // For this setup, startLocationUpdates is called after permission check in onCreate or onRequestPermissionsResult
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        // Consider stopping location updates here to save battery if not needed when paused
        // locationService.stopLocationUpdates()
    }

    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
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
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Location permission is required for map functionality.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLocationUpdates() {
        locationService.startLocationUpdates { location ->
            // 4. Map with user's location centered
            binding.mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
            // Optionally set zoom level once on first fix
            // if (binding.mapView.zoomLevelDouble < SOME_INITIAL_ZOOM_LEVEL) {
            //    binding.mapView.controller.setZoom(SOME_INITIAL_ZOOM_LEVEL)
            // }

            // Country code and airspace loading logic (from previous implementation)
            lifecycleScope.launch {
                val currentCountry = getCountryCodeFromLocation(this@MainActivity, location)
                if (!currentCountry.isNullOrEmpty() && currentCountry != airspaceDataLoadedForCountry) {
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
                // It's good practice to clear specific airspace overlays rather than all,
                // if other overlays (like user location marker) are present.
                // For now, assuming map_view only contains airspace overlays managed here.
                binding.mapView.overlays.removeAll(binding.mapView.overlays.filterNot { it is org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay }) // Example to keep location overlay
                // Or if you only add airspace overlays:
                // binding.mapView.overlays.clear()
                binding.mapView.overlays.addAll(overlays) // Add new ones
                binding.mapView.invalidate() // Refresh the map
                Log.d("MainActivity", "Loaded ${airspaces.size} airspaces for $countryCode")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading airspace data for $countryCode", e)
            }
        }
    }

    private fun showSharePopupMenu(anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        popup.menuInflater.inflate(R.menu.share_options_menu, popup.menu)
        // Attempt to show icons in PopupMenu (reflection-based, might be fragile)
        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup?.javaClass?.getDeclaredMethod("setForceShowIcon", Boolean::class.java)?.invoke(mPopup, true)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error forcing share menu icons.", e)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_save -> { Toast.makeText(this, "Save selected", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_share_xctsk -> { Toast.makeText(this, "Share .xctsk selected", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_share_qrcode -> { Toast.makeText(this, "Share QRCode selected", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_share_cup -> { Toast.makeText(this, "Share .cup selected", Toast.LENGTH_SHORT).show(); true }
                R.id.menu_share_wpt -> { Toast.makeText(this, "Share .wpt selected", Toast.LENGTH_SHORT).show(); true }
                else -> false
            }
        }
        popup.show()
    }

}
