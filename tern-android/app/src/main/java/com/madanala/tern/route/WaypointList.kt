package com.madanala.tern.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WaypointList(modifier: Modifier = Modifier, onDelete: (String) -> Unit = {}) {
    val waypoints by WaypointStore.waypoints.collectAsState()

    Column(modifier = modifier.padding(8.dp)) {
        Text("Waypoints (${waypoints.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn {
            items(waypoints) { wp ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(wp.label ?: "Waypoint")
                        Text("${"%.5f".format(wp.lat)}, ${"%.5f".format(wp.lon)}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onDelete(wp.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete waypoint")
                    }
                }
            }
        }
    }
}
