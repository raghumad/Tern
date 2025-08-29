package com.madanala.tern

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.madanala.tern.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationService: LocationService
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private var airspaceOverlay: ItemizedIconOverlay<OverlayItem>? = null
    private var initialZoomDone = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "tern_settings_prefs"
        private const val PREF_MAP_VIEW = "pref_map_view"
        private const val INITIAL_DEFAULT_ZOOM = 5.0
        private const val USER_LOCATION_ZOOM = 7.0
        private val TAG = MainActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity truly full screen using modern APIs
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            try {
                window.insetsController?.apply {
                    hide(android.view.WindowInsets.Type.statusBars())
                    hide(android.view.WindowInsets.Type.navigationBars())
                    systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to use WindowInsetsController, falling back to legacy approach: ${e.message}")
                // Fallback to legacy approach if insetsController fails
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        } else {
            // Legacy approach for older Android versions
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
        supportActionBar?.hide()

        Configuration.getInstance().load(applicationContext, getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationService = LocationService(this)

        // setupMapView() // REMOVED FROM onCreate, called only in onResume
        checkLocationPermissions() 

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && binding.welcomeScreenOverlay.isVisible) {
                binding.welcomeScreenOverlay.animate()
                    .alpha(0f)
                    .setDuration(1000)
                    .withEndAction {
                        binding.welcomeScreenOverlay.visibility = View.GONE
                    }
                    .start()
            }
        }, 2000)

        binding.settingsButton.setOnClickListener {
            SettingsBottomSheetFragment.newInstance().show(supportFragmentManager, SettingsBottomSheetFragment.TAG)
        }

        binding.addWaypointButton.setOnClickListener {
            Toast.makeText(this, "Add waypoint clicked", Toast.LENGTH_SHORT).show()
        }

        binding.shareMenuButton.setOnClickListener {
            Toast.makeText(this, "Share menu clicked", Toast.LENGTH_SHORT).show()
        }
        loadAirspaceData() 
    }

    private fun setupMapView() {
        Log.d(TAG, "setupMapView called") 
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mapTypePreference = sharedPreferences.getInt(
            PREF_MAP_VIEW,
            R.id.button_map_view_terrain
        )
        var tileSource: ITileSource = TileSourceFactory.OpenTopo

        if (mapTypePreference == R.id.button_map_view_satellite) {
            val preferredSatelliteSourceNames = listOf(
                "Microsoft Satellite", "Microsoft Hybrid", "Bing Satellite", "Esri WorldImagery", "USGS Sat"
            )
            var foundSatelliteSource: ITileSource? = null
            for (name in preferredSatelliteSourceNames) {
                try {
                    val sourceByName = TileSourceFactory.getTileSource(name)
                    if (sourceByName != null) {
                        foundSatelliteSource = sourceByName
                        Log.d(TAG, "Found satellite source by name: $name")
                        break
                    }
                } catch (e: IllegalArgumentException) {
                    Log.d(TAG, "Tile source not found by name: $name")
                }
            }
            if (foundSatelliteSource == null) {
                val availableSources = TileSourceFactory.getTileSources()
                foundSatelliteSource = availableSources.firstOrNull {
                    val n = it.name().lowercase(Locale.ROOT)
                    val found = n.contains("satellite") || n.contains("aerial") || n.contains("hybrid") || n.contains("imagery")
                    if (found) Log.d(TAG, "Found satellite source by keyword: ${it.name()}")
                    found
                }
            }
            tileSource = foundSatelliteSource ?: TileSourceFactory.USGS_SAT
            if (foundSatelliteSource == null) Log.d(TAG, "Using fallback satellite USGS_SAT for satellite preference.")
        } else {
            Log.d(TAG, "Using OpenTopo tile source for terrain preference.")
        }

        binding.mapView.setTileSource(tileSource)
        binding.mapView.setMultiTouchControls(true)
        addMapOverlays() 

        val mapController = binding.mapView.controller
        if (!initialZoomDone) {
            mapController.setZoom(INITIAL_DEFAULT_ZOOM)
        }
    }

    private fun addMapOverlays() {
        Log.d(TAG, "addMapOverlays called") 
        val overlays = binding.mapView.overlays
        overlays.removeAll { it is CopyrightOverlay || it is ScaleBarOverlay || it is CompassOverlay || it is RotationGestureOverlay }
        Log.d(TAG, "Cleared old cosmetic overlays. Current overlay count after clearing: ${overlays.size}")

        val copyrightOverlay = CopyrightOverlay(this)
        binding.mapView.overlays.add(copyrightOverlay)

        val scaleBarOverlay = ScaleBarOverlay(binding.mapView)
        scaleBarOverlay.setCentred(true)
        scaleBarOverlay.setScaleBarOffset(resources.displayMetrics.widthPixels / 2, 10)
        binding.mapView.overlays.add(scaleBarOverlay)

        // Use screen dimensions as reliable fallback since map view isn't laid out yet
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // Create custom compass overlay using the new visual control
        val compassOverlay = CustomCompassOverlay(screenWidth, screenHeight)
        Log.d(TAG, "Custom compass overlay created")

        // Compass overlay is now positioned internally in the CustomCompassOverlay class

        // Add compass as the TOPMOST overlay to ensure visibility
        binding.mapView.overlays.add(compassOverlay)
        Log.d(TAG, "CompassOverlay added as top overlay. Total overlays: ${overlays.size}")

        // Force redraw
        binding.mapView.invalidate()
        Log.d(TAG, "Map invalidated")

        // ** mapView.post block for top-right positioning REMOVED for this test **

        val rotationGestureOverlay = RotationGestureOverlay(binding.mapView)
        rotationGestureOverlay.isEnabled = true
        binding.mapView.overlays.add(rotationGestureOverlay)

        if (!::myLocationOverlay.isInitialized) {
            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
            myLocationOverlay.isDrawAccuracyEnabled = true

            // Set a consistent arrow icon for both moving and stationary states
            try {
                val arrowBitmap = BitmapFactory.decodeResource(resources, android.R.drawable.arrow_up_float)
                if (arrowBitmap != null) {
                    myLocationOverlay.setPersonIcon(arrowBitmap)
                    Log.d(TAG, "MyLocationNewOverlay initialized with consistent arrow icon.")
                } else {
                    Log.w(TAG, "Could not load arrow drawable, using default icons.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error setting custom icon: ${e.message}, using default icons.")
            }
        }
        if (!binding.mapView.overlays.contains(myLocationOverlay)) {
            binding.mapView.overlays.add(myLocationOverlay)
            Log.d(TAG, "MyLocationNewOverlay added to map overlays.")
        }

        Log.d(TAG, "--- Final Overlay List in addMapOverlays (total: ${binding.mapView.overlays.size}) ---")
        binding.mapView.overlays.forEachIndexed { index, overlay ->
            Log.d(TAG, "Overlay $index: ${overlay.javaClass.simpleName} (Enabled: ${overlay.isEnabled})")
        }
        Log.d(TAG, "--- End of Overlay List ---")
    }


    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, "Permissions already granted.")
        }
    }

    private fun requestLocationUpdatesFromHelper() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "requestLocationUpdatesFromHelper called.")
            if(::myLocationOverlay.isInitialized) { 
                myLocationOverlay.enableMyLocation()
                myLocationOverlay.enableFollowLocation()
                Log.d(TAG, "MyLocationOverlay enabled and following.")

                // Let OSMDroid use default location icons for consistency
                Log.d(TAG, "Using default OSMDroid location icons")
            } else {
                Log.e(TAG, "myLocationOverlay not initialized when trying to enable location or set icon in requestLocationUpdatesFromHelper.")
            }
            locationService.startLocationUpdates { location ->
                updateMapLocation(location)
            }
        } else {
            Log.w(TAG, "Fine location permission not granted. Cannot request location updates from requestLocationUpdatesFromHelper.")
        }
    }

    private fun updateMapLocation(location: Location) {
        if (!initialZoomDone) {
            binding.mapView.controller.setZoom(USER_LOCATION_ZOOM)
            initialZoomDone = true
            Log.d(TAG, "Set user location zoom: $USER_LOCATION_ZOOM")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "Location permissions granted via dialog.")
            } else {
                Log.w(TAG, "Location permissions denied via dialog.")
                Toast.makeText(this, "Location permission denied. Map will not center on your location.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Location permission is needed to show your current location on the map. Please enable it in app settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).also { intent ->
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAirspaceData() {
        Log.d(TAG, "loadAirspaceData called.")
        if (::myLocationOverlay.isInitialized) {
            val overlaysToRemove = binding.mapView.overlays.filter { overlay ->
                // Remove if it's an ItemizedIconOverlay AND it's NOT our specific myLocationOverlay instance
                overlay is ItemizedIconOverlay<*> && overlay != myLocationOverlay
            }
            overlaysToRemove.forEach { binding.mapView.overlays.remove(it) }
        } else {
            // Fallback: If myLocationOverlay isn't initialized, remove all ItemizedIconOverlays
            // This assumes airspace overlays are ItemizedIconOverlay<*>
            val overlaysToRemove = binding.mapView.overlays.filterIsInstance<ItemizedIconOverlay<*>>()
            overlaysToRemove.forEach { binding.mapView.overlays.remove(it) }
        }
        airspaceOverlay = null // Assuming this is the main type of overlay being managed here
        Log.d(TAG, "Cleared old airspace overlays. Count after airspace clear: ${binding.mapView.overlays.size}")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called.")
        setupMapView() 
        binding.mapView.onResume() 

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "onResume: Permissions granted. Requesting location updates helper.")
            requestLocationUpdatesFromHelper()
        } else {
            Log.d(TAG, "onResume: Permissions not yet granted. Not calling requestLocationUpdatesFromHelper.")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called.")
        binding.mapView.onPause()
        Log.d(TAG, "onPause: Stopping location updates.")
        locationService.stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called.")
    }
}
