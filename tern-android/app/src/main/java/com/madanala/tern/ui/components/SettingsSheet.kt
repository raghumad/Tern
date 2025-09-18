package com.madanala.tern.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Tornado
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.R
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    mapViewModel: MapViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    // Notify map view model when airspace settings change
    val showAirspaces by settingsViewModel::showAirspaces
    androidx.compose.runtime.LaunchedEffect(showAirspaces) {
        mapViewModel.setAirspacesEnabled(showAirspaces)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            item {
                Text("Map Layers", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                SettingsToggleRow(
                    text = "Airspaces",
                    isChecked = settingsViewModel.showAirspaces,
                    onCheckedChange = settingsViewModel::onShowAirspacesChanged
                ) {
                    Icon(Icons.Filled.AirplanemodeActive, contentDescription = "Airspaces")
                }
                SettingsToggleRow(
                    text = "Hotspots",
                    isChecked = settingsViewModel.showHotspots,
                    onCheckedChange = settingsViewModel::onShowHotspotsChanged
                ) {
                    Icon(Icons.Filled.Tornado, contentDescription = "Hotspots")
                }
                SettingsToggleRow(
                    text = "PGSpots",
                    isChecked = settingsViewModel.showPgSpots,
                    onCheckedChange = settingsViewModel::onShowPgSpotsChanged
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.kjartan_birgisson),
                        contentDescription = "PGSpots",
                        modifier = Modifier.size(24.dp)
                    )
                }
                Divider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                Text("Units", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                SettingsPickerRow(
                    label = "Map Style",
                    items = listOf("Terrain", "Satellite"),
                    selectedItem = if (mapViewModel.mapStyle == MAP_VIEW_TERRAIN) "Terrain" else "Satellite",
                    onItemSelected = {
                        val newStyle = if (it == "Terrain") MAP_VIEW_TERRAIN else MAP_VIEW_SATELLITE
                        mapViewModel.updateMapStyle(newStyle)
                    }
                )
                SettingsPickerRow(
                    label = "Temperature",
                    items = listOf("°F", "°C", "K"),
                    selectedItem = settingsViewModel.temperatureUnit,
                    onItemSelected = settingsViewModel::onTemperatureUnitChanged
                )
                SettingsPickerRow(
                    label = "Distance",
                    items = listOf("km", "mi", "fur"),
                    selectedItem = settingsViewModel.distanceUnit,
                    onItemSelected = settingsViewModel::onDistanceUnitChanged
                )
                SettingsPickerRow(
                    label = "Speed",
                    items = listOf("kn", "mph", "kph", "m/s"),
                    selectedItem = settingsViewModel.speedUnit,
                    onItemSelected = settingsViewModel::onSpeedUnitChanged
                )
                SettingsPickerRow(
                    label = "Altitude",
                    items = listOf("ft", "m", "in"),
                    selectedItem = settingsViewModel.altitudeUnit,
                    onItemSelected = settingsViewModel::onAltitudeUnitChanged
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    text: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        icon()
        Text(text, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Switch(checked = isChecked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsPickerRow(
    label: String,
    items: List<String>,
    selectedItem: String,
    onItemSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Row {
            items.forEachIndexed { index, item ->
                OutlinedButton(
                    onClick = { onItemSelected(item) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (item == selectedItem) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (item == selectedItem) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(36.dp)
                        .padding(horizontal = 2.dp)
                ) {
                    Text(item, fontSize = 12.sp)
                }
            }
        }
    }
}
