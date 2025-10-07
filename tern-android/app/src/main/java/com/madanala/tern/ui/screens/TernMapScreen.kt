package com.madanala.tern.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.WeatherActions
import com.madanala.tern.ui.components.AddWaypointButton
import com.madanala.tern.ui.components.MapViewContainer
import com.madanala.tern.ui.components.MapViewModel
import com.madanala.tern.ui.components.SettingsButton
import com.madanala.tern.ui.components.SettingsSheet
import com.madanala.tern.ui.components.ShareButton
import com.madanala.tern.ui.components.ShareSheet
import com.madanala.tern.ui.components.WeatherDetailsDialog
import com.madanala.tern.ui.components.WelcomeScreen

// Constants for map styles, moved from MainActivity for broader access
const val MAP_VIEW_TERRAIN = 1
const val MAP_VIEW_SATELLITE = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TernMapScreen(
    modifier: Modifier = Modifier,
    store: MapStore = viewModel()
) {
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    val state by store.state.collectAsState()
    val isLocationReady = state.isLocationReady
    val gpsStatus = state.gpsStatus
    val mapViewModel = viewModel<MapViewModel>()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            MapViewContainer(
                modifier = Modifier.fillMaxSize(),
                store = store
            )
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .align(Alignment.CenterEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsButton(onClick = { showSettingsSheet = true })
                AddWaypointButton()
                ShareButton(onClick = { showShareSheet = true })
            }
            AnimatedVisibility(
                visible = !isLocationReady,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                WelcomeScreen(gpsStatus = gpsStatus)
            }
        }
    }


    if (showSettingsSheet) {
        SettingsSheet(
            onDismiss = { showSettingsSheet = false },
            store = store
        )
    }

    if (showShareSheet) {
        ShareSheet(onDismiss = { showShareSheet = false })
    }

    // Show weather details dialog when PG spot is tapped
    state.weatherState.showingWeatherDialog?.let { (pgSpotId, forecast) ->
        WeatherDetailsDialog(
            forecast = forecast,
            spotName = pgSpotId,
            onDismiss = {
                store.dispatch(WeatherActions.DismissWeatherDetails)
            }
        )
    }
}
