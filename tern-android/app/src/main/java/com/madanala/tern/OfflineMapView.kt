package com.madanala.tern

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.mylocation.SimpleLocationOverlay
import org.osmdroid.views.overlay.Polygon

class OfflineMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var cacheManager: CacheManager

    init {
        setupMap()
    }

    private fun setupMap() {
        // Configure OSMDroid
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        
        // Inflate the layout
        LayoutInflater.from(context).inflate(R.layout.view_offline_map, this, true)
        
        // Get the MapView reference
        mapView = findViewById(R.id.map_view)
        
        // Setup location overlay
        myLocationOverlay = MyLocationNewOverlay(mapView)
        myLocationOverlay.enableMyLocation()
        mapView.overlays.add(myLocationOverlay)
        
        // Setup cache manager for offline tiles
        cacheManager = CacheManager(mapView)
        
        // Set initial view (you can adjust these coordinates)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(10.0)
        mapView.controller.setCenter(GeoPoint(37.7749, -122.4194)) // Default to San Francisco, change as needed
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun setLocation(latitude: Double, longitude: Double) {
        val point = GeoPoint(latitude, longitude)
        mapView.controller.animateTo(point)
        myLocationOverlay.enableFollowLocation()
    }

    fun getMapView(): MapView = mapView

    fun getCacheManager(): CacheManager = cacheManager
    
    fun addAirspaceOverlays(overlays: List<Polygon>) {
        mapView.overlays.addAll(overlays)
        mapView.invalidate()
    }
    
    fun clearAirspaceOverlays() {
        // Remove all polygon overlays except location overlay
        val nonPolygonOverlays = mapView.overlays.filter { it !is Polygon }
        mapView.overlays.clear()
        mapView.overlays.addAll(nonPolygonOverlays)
        mapView.invalidate()
    }
}
