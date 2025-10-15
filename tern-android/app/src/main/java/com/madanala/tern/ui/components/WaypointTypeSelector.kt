package com.madanala.tern.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.madanala.tern.model.Waypoint

/**
 * Waypoint type selection dialog for creating or editing waypoints
 * Provides clear visual representation of waypoint types with aviation-specific icons
 */
private data class WaypointOptionData(
    val icon: String,
    val title: String,
    val description: String
)

private val WaypointTypeData = mapOf(
    Waypoint.Type.LAUNCH to WaypointOptionData("🚀", "Launch", "Starting point for your flight"),
    Waypoint.Type.TURNPOINT to WaypointOptionData("⭕", "Turnpoint", "Intermediate waypoint for navigation"),
    Waypoint.Type.LANDING to WaypointOptionData("🎯", "Landing", "Designated landing zone")
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointTypeSelector(
    onTypeSelected: (Waypoint.Type) -> Unit,
    onDismiss: () -> Unit,
    currentType: Waypoint.Type? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Select Waypoint Type",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Waypoint.Type.values().forEach { type ->
                    WaypointTypeOption(
                        type = type,
                        isSelected = type == currentType,
                        onClick = {
                            onTypeSelected(type)
                            onDismiss()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun WaypointTypeOption(
    type: Waypoint.Type,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val data = WaypointTypeData[type] ?: WaypointOptionData("❓", "Unknown", "Unknown type")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = data.icon,
                fontSize = 24.sp,
                modifier = Modifier.width(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = data.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Text(
                    text = "✓",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
