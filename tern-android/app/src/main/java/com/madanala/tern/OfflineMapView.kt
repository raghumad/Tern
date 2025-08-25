package com.madanala.tern

import android.content.Context
import android.util.AttributeSet
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView // Ensure this import is present
import org.osmdroid.config.Configuration
import android.graphics.Color // For background color
import org.osmdroid.util.GeoPoint // For setting center
import androidx.preference.PreferenceManager // For default SharedPreferences

class OfflineMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MapView(context, attrs) { // **** THIS LINE IS CRUCIAL: Extends MapView ****

    init {
        // Load osmdroid configuration.
        // Using default SharedPreferences for context.
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        setupMap()
    }

    private fun setupMap() {
        // 'this' is now the MapView itself
        // Line 26 from your error was the call to setupMap from init.
        // The content of setupMap starts here.

        this.setTileSource(TileSourceFactory.MAPNIK)
        this.setBackgroundColor(Color.parseColor("#E0E0E0")) // Example background
        this.setMultiTouchControls(true)
        this.controller.setZoom(10.0)
        this.controller.setCenter(GeoPoint(51.5074, 0.1278)) // Default center (e.g., London)

        // Line 37 from your error was likely one of the lines above,
        // depending on the exact old structure and comments.
        // Example: If it was this.findViewById<MapView>(R.id.map_view), that would cause a cast error
        // if 'this' wasn't already a MapView or if R.id.map_view was the wrong type.
        // Now, these direct calls on 'this' are correct because 'this' IS a MapView.
    }

    // Methods like onResume, onPause, setLocation, addAirspaceOverlays
    // are now inherited from the MapView class.
    // MainActivity can call them directly on an OfflineMapView instance.
}
