package com.madanala.tern.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.madanala.tern.ui.components.AddWaypointButton
import com.madanala.tern.ui.components.MapViewContainer
import com.madanala.tern.ui.components.SettingsButton
import com.madanala.tern.ui.components.SettingsSheet
import com.madanala.tern.ui.components.ShareButton
import com.madanala.tern.ui.components.ShareSheet

// Constants for map styles, moved from MainActivity for broader access
const val MAP_VIEW_TERRAIN = 1
const val MAP_VIEW_SATELLITE = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TernMapScreen(
    mapStyle: Int,
    updateMapStyle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        MapViewContainer(
            modifier = Modifier.fillMaxSize(),
            mapStyle = mapStyle
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsButton(onClick = { showSettingsSheet = true })
            AddWaypointButton()
            ShareButton(onClick = { showShareSheet = true })
        }
    }

    if (showSettingsSheet) {
        SettingsSheet(
            onDismiss = { showSettingsSheet = false },
            onUpdateMapStyle = updateMapStyle
        )
    }

    if (showShareSheet) {
        ShareSheet(onDismiss = { showShareSheet = false })
    }
}
