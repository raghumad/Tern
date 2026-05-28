package com.ternparagliding.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DOCK_BUTTON_SIZE = 48.dp
private val DOCK_BG = Color(0xFF1A1A2E).copy(alpha = 0.85f)
private val DOCK_ICON = Color.White
private val DOCK_ELEVATION = 4.dp

/**
 * Wraps a Material IconButton in a dark semi-transparent pill with a
 * subtle shadow so the icon stays legible over both light terrain
 * tiles and dark forest/shadow tiles. Same visual style across all
 * map-dock buttons.
 */
@Composable
private fun DockIconBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(DOCK_BUTTON_SIZE)
            .shadow(elevation = DOCK_ELEVATION, shape = CircleShape)
            .background(color = DOCK_BG, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    DockIconBackground {
        IconButton(onClick = onClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DOCK_ICON)
        }
    }
}

@Composable
fun AddWaypointButton() {
    val context = LocalContext.current
    DockIconBackground {
        IconButton(onClick = {
            Toast.makeText(context, "Add waypoint clicked", Toast.LENGTH_SHORT).show()
        }) {
            Icon(Icons.Default.Add, contentDescription = "Add Waypoint", tint = DOCK_ICON)
        }
    }
}

@Composable
fun ShareButton(onClick: () -> Unit) {
    DockIconBackground {
        IconButton(onClick = onClick) {
            Icon(Icons.Default.MoreVert, contentDescription = "Share Menu", tint = DOCK_ICON)
        }
    }
}

@Composable
fun RouteButton(onClick: () -> Unit) {
    DockIconBackground {
        IconButton(onClick = onClick) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = com.ternparagliding.R.drawable.route_24),
                contentDescription = "Route Management",
                tint = DOCK_ICON,
            )
        }
    }
}
