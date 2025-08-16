package com.madanala.tern

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.madanala.tern.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationService: LocationService
    private lateinit var airspaceService: AirspaceService
    
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        locationService = LocationService(this)
        airspaceService = AirspaceService(this)
        
        // Check and request permissions
        checkLocationPermissions()
        
        // Setup map lifecycle
        binding.offlineMapView.onResume()
        
        // Load airspace data
        loadAirspaceData()
    }
    
    override fun onResume() {
        super.onResume()
        binding.offlineMapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        binding.offlineMapView.onPause()
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
            binding.offlineMapView.setLocation(location.latitude, location.longitude)
        }
    }
    
    private fun loadAirspaceData() {
        lifecycleScope.launch {
            try {
                // For now, let's load US airspace data
                // You can make this configurable later
                val airspaces = airspaceService.loadAirspaces("US")
                val overlays = airspaceService.createAirspaceOverlays(airspaces)
                
                binding.offlineMapView.addAirspaceOverlays(overlays)
                
                // Log success
                android.util.Log.d("MainActivity", "Loaded ${airspaces.size} airspaces")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error loading airspace data", e)
            }
        }
    }
}
