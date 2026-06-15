package com.ternparagliding.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DOCK_BUTTON_SIZE = 40.dp
private val DOCK_ICON_SIZE = 22.dp  // ~55% of button — leaves a comfortable visible pill ring
private val DOCK_BG = Color(0xFF1A1A2E).copy(alpha = 0.55f)
private val DOCK_ICON = Color.White
private val DOCK_ELEVATION = 3.dp

/**
 * One dock control: a clickable circular hit target with a soft shadow
 * + a moderate dark pill. Shadow alone proved unreliable over light
 * terrain (snow / cloud cover); pill at 55% alpha is visible without
 * dominating the map.
 *
 * We deliberately do NOT wrap a Material IconButton — IconButton
 * imposes its own 48dp internal sizing that misaligns the icon inside
 * any smaller outer Box.
 */
@Composable
private fun DockButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DOCK_BUTTON_SIZE)
            .shadow(elevation = DOCK_ELEVATION, shape = CircleShape)
            .background(color = DOCK_BG, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(Modifier.size(DOCK_ICON_SIZE))
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    DockButton(onClick, "Settings") { m ->
        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DOCK_ICON, modifier = m)
    }
}

@Composable
fun AddWaypointButton() {
    val context = LocalContext.current
    DockButton({ Toast.makeText(context, "Add waypoint clicked", Toast.LENGTH_SHORT).show() }, "Add Waypoint") { m ->
        Icon(Icons.Default.Add, contentDescription = "Add Waypoint", tint = DOCK_ICON, modifier = m)
    }
}

@Composable
fun ShareButton(onClick: () -> Unit) {
    DockButton(onClick, "Share") { m ->
        Icon(Icons.Default.Share, contentDescription = "Share", tint = DOCK_ICON, modifier = m)
    }
}

/** Recenter the map on the pilot's current location. Disabled tint until a fix exists. */
@Composable
fun RecenterButton(enabled: Boolean, onClick: () -> Unit) {
    DockButton(onClick, "Recenter on me") { m ->
        Icon(
            Icons.Default.MyLocation,
            contentDescription = "Recenter on me",
            tint = if (enabled) DOCK_ICON else DOCK_ICON.copy(alpha = 0.4f),
            modifier = m,
        )
    }
}

@Composable
fun VarioConnectButton(connected: Boolean, scanning: Boolean, onClick: () -> Unit) {
    // A Bluetooth glyph (not the old "wind/Air" icon, which read as a weather
    // control): this button pairs the external XC Tracer vario over BLE. The
    // icon and tint both track link state so the state is unambiguous.
    val tint = when {
        connected -> Color(0xFF22C55E) // green = streaming
        scanning -> Color(0xFFF59E0B)  // amber = scanning
        else -> DOCK_ICON
    }
    val icon = when {
        connected -> Icons.Default.BluetoothConnected
        scanning -> Icons.Default.BluetoothSearching
        else -> Icons.Default.Bluetooth
    }
    DockButton(onClick, "Connect vario") { m ->
        Icon(icon, contentDescription = "Connect vario", tint = tint, modifier = m)
    }
}

@Composable
fun TaskButton(onClick: () -> Unit) {
    DockButton(onClick, "Task Management") { m ->
        Icon(
            painter = androidx.compose.ui.res.painterResource(id = com.ternparagliding.R.drawable.route_24),
            contentDescription = "Task Management",
            tint = DOCK_ICON,
            modifier = m,
        )
    }
}
