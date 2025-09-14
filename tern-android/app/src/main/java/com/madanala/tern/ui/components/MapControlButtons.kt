package com.madanala.tern.ui.components

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }
}

@Composable
fun AddWaypointButton() {
    val context = LocalContext.current
    IconButton(onClick = {
        Toast.makeText(context, "Add waypoint clicked", Toast.LENGTH_SHORT).show()
    }) {
        Icon(Icons.Default.Add, contentDescription = "Add Waypoint")
    }
}

@Composable
fun ShareButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.MoreVert, contentDescription = "Share Menu")
    }
}
