package com.madanala.tern.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AddWaypointButton(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    mapViewModel: MapViewModel = viewModel()
) {
    var isEditMode by remember { mutableStateOf(false) }

    FloatingActionButton(
        onClick = {
            isEditMode = !isEditMode
            if (isEditMode) {
                mapViewModel.enableRouteEditMode()
            } else {
                mapViewModel.disableRouteEditMode()
            }
        },
        modifier = modifier,
        containerColor = if (isEditMode) Color.Red else Color.Blue,
        contentColor = Color.White
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = if (isEditMode) "Exit Route Edit Mode" else "Enter Route Edit Mode"
        )
    }
}