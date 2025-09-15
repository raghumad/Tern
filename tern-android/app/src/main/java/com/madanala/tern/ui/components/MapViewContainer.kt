package com.madanala.tern.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.madanala.tern.CustomCompassOverlay
import com.madanala.tern.LocationService
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

private const val INITIAL_DEFAULT_ZOOM = 5.0
private const val USER_LOCATION_ZOOM = 7.0
private val TAG = "MapViewContainer"

class MapState(
    val mapView: MapView,
    private val locationService: LocationService,
    private val context: Context
) {
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var initialZoomDone = false

    fun setupMapView(style: Int) {
        Log.d(TAG, "setupMapView called with style: $style")
        val tileSource: ITileSource = if (style == MAP_VIEW_SATELLITE) {
            findSatelliteTileSource()
        } else {
            Log.d(TAG, "Using OpenTopo tile source for terrain preference.")
            TileSourceFactory.OpenTopo
        }

        mapView.setTileSource(tileSource)
        addMapOverlays()

        if (!initialZoomDone) {
            mapView.controller.setZoom(INITIAL_DEFAULT_ZOOM)
        }
    }

    fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "requestLocationUpdates called.")
            myLocationOverlay?.enableMyLocation()
            myLocationOverlay?.enableFollowLocation()
            locationService.startLocationUpdates { _ ->
                if (!initialZoomDone) {
                    mapView.controller.setZoom(USER_LOCATION_ZOOM)
                    initialZoomDone = true
                    Log.d(TAG, "Set user location zoom: $USER_LOCATION_ZOOM")
                }
            }
        } else {
            Log.w(TAG, "Fine location permission not granted. Cannot request location updates.")
        }
    }

    private fun findSatelliteTileSource(): ITileSource {
        val preferredSatelliteSourceNames = listOf(
            "Microsoft Satellite", "Microsoft Hybrid", "Bing Satellite", "Esri WorldImagery", "USGS Sat"
        )
        for (name in preferredSatelliteSourceNames) {
            try {
                TileSourceFactory.getTileSource(name)?.let {
                    Log.d(TAG, "Found satellite source by name: $name")
                    return it
                }
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Tile source not found by name: $name")
            }
        }

        val availableSources = TileSourceFactory.getTileSources()
        for (tileSource in availableSources) {
            val n = tileSource.name().lowercase(Locale.ROOT)
            if (n.contains("satellite") || n.contains("aerial") || n.contains("hybrid") || n.contains("imagery")) {
                Log.d(TAG, "Found satellite source by keyword: ${tileSource.name()}")
                return tileSource
            }
        }

        Log.d(TAG, "Using fallback satellite USGS_SAT for satellite preference.")
        return TileSourceFactory.USGS_SAT
    }

    private fun addMapOverlays() {
        Log.d(TAG, "addMapOverlays called")
        with(mapView.overlays) {
            removeAll { it is CopyrightOverlay || it is ScaleBarOverlay || it is CompassOverlay || it is RotationGestureOverlay }
            Log.d(TAG, "Cleared old cosmetic overlays. Current overlay count after clearing: $size")

            add(CopyrightOverlay(context))
            add(ScaleBarOverlay(mapView).apply {
                setCentred(true)
                setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, 10)
            })

            val displayMetrics = context.resources.displayMetrics
            add(CustomCompassOverlay(displayMetrics.widthPixels.toFloat(), displayMetrics.heightPixels.toFloat()))
            Log.d(TAG, "Custom compass overlay created and added.")

            add(RotationGestureOverlay(mapView).apply { isEnabled = true })

            if (myLocationOverlay == null) {
                myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
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
            }
            if (!contains(myLocationOverlay)) {
                add(myLocationOverlay)
            }
        }
        mapView.invalidate()
    }
}

@Composable
fun rememberMapState(): MapState {
    val context = LocalContext.current
    val locationService = remember { LocationService(context) }
    val mapState = remember {
        MapState(
            mapView = MapView(context).apply {
                setMultiTouchControls(true)
                minZoomLevel = 3.0
                maxZoomLevel = 20.0
                controller.setZoom(INITIAL_DEFAULT_ZOOM)
            },
            locationService = locationService,
            context = context
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapState.mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapState.mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return mapState
}

@Composable
fun MapViewContainer(
    modifier: Modifier = Modifier,
    mapStyle: Int
) {
    val context = LocalContext.current
    val mapState = rememberMapState()

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
            mapState.requestLocationUpdates()
        }
    }

    LaunchedEffect(mapStyle) {
        mapState.setupMapView(mapStyle)
    }

    AndroidView({ mapState.mapView }, modifier = modifier)
}
