package com.ternparagliding.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Spike: does MapLibre render with OpenFreeMap tiles?
 * Minimal — just the map, centered on the Aravis.
 */
@Composable
fun MapLibreSpike() {
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = 6.5, latitude = 45.8),
            zoom = 10.0,
        )
    )

    MaplibreMap(
        Modifier.fillMaxSize(),
        BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
        cameraState,
    )
}
