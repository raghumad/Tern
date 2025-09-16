package com.madanala.tern.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.madanala.tern.LocationService
import com.madanala.tern.R
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

private const val INITIAL_DEFAULT_ZOOM = 5.0
private const val USER_LOCATION_ZOOM = 7.0
private const val TAG = "MapViewContainer"

class MapState(
    val mapView: MapView,
    private val locationService: LocationService,
    private val context: Context
) {
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var initialZoomDone = false

    val mapRotation = MutableStateFlow(mapView.mapOrientation)

    init {
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                mapRotation.value = mapView.mapOrientation
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                mapRotation.value = mapView.mapOrientation
                return false
            }
        })
    }

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
            locationService.startLocationUpdates { location ->
                if (!initialZoomDone) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    mapView.controller.animateTo(geoPoint, USER_LOCATION_ZOOM, null)
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
            } catch (_: IllegalArgumentException) {
                // Ignore
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
            removeAll { it is CopyrightOverlay || it is ScaleBarOverlay || it is RotationGestureOverlay }
            Log.d(TAG, "Cleared old cosmetic overlays. Current overlay count after clearing: $size")

            add(CopyrightOverlay(context))
            add(ScaleBarOverlay(mapView).apply {
                setCentred(true)
                setScaleBarOffset(context.resources.displayMetrics.widthPixels / 2, 10)
            })

            add(RotationGestureOverlay(mapView).apply { isEnabled = true })

            if (myLocationOverlay == null) {
                myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
                    isDrawAccuracyEnabled = true
                    try {
                        val customIcon: Drawable? = AppCompatResources.getDrawable(context, R.drawable.paragliding_24)
                        customIcon?.let {
                            setPersonIcon(it.toBitmap())
                            setDirectionIcon(it.toBitmap())
                            Log.d(TAG, "MyLocationNewOverlay initialized with custom paragliding icon and hotspot.")
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
    val mapRotation by mapState.mapRotation.collectAsState()

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

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView({ mapState.mapView }, modifier = Modifier.fillMaxSize())

        Compass(
            rotation = mapRotation,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(16.dp)
        )
    }
}
