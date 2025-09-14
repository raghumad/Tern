package com.madanala.tern.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.madanala.tern.CustomCompassOverlay
import com.madanala.tern.LocationService
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

private const val INITIAL_DEFAULT_ZOOM = 5.0
private const val USER_LOCATION_ZOOM = 7.0
private val TAG = "MapViewContainer"

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    mapStyle: Int,
    updateMapStyle: (Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var myLocationOverlay: MyLocationNewOverlay? by remember { mutableStateOf(null) }
    var initialZoomDone by remember { mutableStateOf(false) }

    val locationService = remember { LocationService(context) }

    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(INITIAL_DEFAULT_ZOOM)
        }
    }

    fun setupMapView(style: Int) {
        Log.d(TAG, "setupMapView called with style: $style")
        var tileSource: ITileSource = TileSourceFactory.OpenTopo

        if (style == MAP_VIEW_SATELLITE) {
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

        mapView.setTileSource(tileSource)
        addMapOverlays(context, mapView, myLocationOverlay) { myLocationOverlay = it }

        val mapController = mapView.controller
        if (!initialZoomDone) {
            mapController.setZoom(INITIAL_DEFAULT_ZOOM)
        }
    }
    
    fun updateMapLocation(location: Location) {
        if (!initialZoomDone) {
            mapView.controller.setZoom(USER_LOCATION_ZOOM)
            initialZoomDone = true
            Log.d(TAG, "Set user location zoom: $USER_LOCATION_ZOOM")
        }
    }
    
    fun requestLocationUpdatesFromHelper() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "requestLocationUpdatesFromHelper called.")
            myLocationOverlay?.let {
                it.enableMyLocation()
                it.enableFollowLocation()
                Log.d(TAG, "MyLocationOverlay enabled and following.")
            }
            locationService.startLocationUpdates { location ->
                updateMapLocation(location)
            }
        } else {
            Log.w(TAG, "Fine location permission not granted. Cannot request location updates from requestLocationUpdatesFromHelper.")
        }
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                hasLocationPermission = true
            } else {
                Toast.makeText(context, "Location permission denied. Map will not center on your location.", Toast.LENGTH_LONG).show()
            }
        }
    )

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            requestLocationUpdatesFromHelper()
        }
    }

    LaunchedEffect(mapStyle) {
        setupMapView(mapStyle)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView({ mapView }, modifier = modifier)
}


private fun addMapOverlays(
    context: Context,
    mapView: MapView,
    myLocationOverlay: MyLocationNewOverlay?,
    setMyLocationOverlay: (MyLocationNewOverlay) -> Unit
) {
    Log.d(TAG, "addMapOverlays called")
    val overlays = mapView.overlays
    overlays.removeAll { it is CopyrightOverlay || it is ScaleBarOverlay || it is CompassOverlay || it is RotationGestureOverlay }
    Log.d(TAG, "Cleared old cosmetic overlays. Current overlay count after clearing: ${overlays.size}")

    val copyrightOverlay = CopyrightOverlay(context)
    mapView.overlays.add(copyrightOverlay)

    val scaleBarOverlay = ScaleBarOverlay(mapView)
    scaleBarOverlay.setCentred(true)
    scaleBarOverlay.setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, 10)
    mapView.overlays.add(scaleBarOverlay)

    val displayMetrics = context.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels.toFloat()
    val screenHeight = displayMetrics.heightPixels.toFloat()

    val compassOverlay = CustomCompassOverlay(screenWidth, screenHeight)
    Log.d(TAG, "Custom compass overlay created")

    mapView.overlays.add(compassOverlay)
    Log.d(TAG, "CompassOverlay added as top overlay. Total overlays: ${overlays.size}")

    mapView.invalidate()
    Log.d(TAG, "Map invalidated")

    val rotationGestureOverlay = RotationGestureOverlay(mapView)
    rotationGestureOverlay.isEnabled = true
    mapView.overlays.add(rotationGestureOverlay)

    if (myLocationOverlay == null) {
        val newMyLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            isDrawAccuracyEnabled = true
            try {
                val arrowBitmap = BitmapFactory.decodeResource(context.resources, android.R.drawable.arrow_up_float)
                if (arrowBitmap != null) {
                    setPersonIcon(arrowBitmap)
                    Log.d(TAG, "MyLocationNewOverlay initialized with consistent arrow icon.")
                } else {
                    Log.w(TAG, "Could not load arrow drawable, using default icons.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error setting custom icon: ${e.message}, using default icons.")
            }
        }
        setMyLocationOverlay(newMyLocationOverlay)
        mapView.overlays.add(newMyLocationOverlay)
        Log.d(TAG, "MyLocationNewOverlay added to map overlays.")
    } else {
        if (!mapView.overlays.contains(myLocationOverlay)) {
            mapView.overlays.add(myLocationOverlay)
        }
    }
}

fun loadAirspaceData(mapView: MapView, myLocationOverlay: MyLocationNewOverlay?) {
    Log.d(TAG, "loadAirspaceData called.")
    myLocationOverlay?.let {
        val overlaysToRemove = mapView.overlays.filter { overlay ->
            overlay is ItemizedIconOverlay<*> && overlay != it
        }
        overlaysToRemove.forEach { mapView.overlays.remove(it) }
    } ?: run {
        val overlaysToRemove = mapView.overlays.filterIsInstance<ItemizedIconOverlay<*>>()
        overlaysToRemove.forEach { mapView.overlays.remove(it) }
    }
    Log.d(TAG, "Cleared old airspace overlays. Count after airspace clear: ${mapView.overlays.size}")
}
