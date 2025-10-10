package com.madanala.tern.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.madanala.tern.model.Waypoint

@Composable
fun TypeSelectionSheet(onSelect: (Waypoint.Type) -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Select waypoint type", style = MaterialTheme.typography.titleMedium)
        Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { onSelect(Waypoint.Type.LAUNCH) }) {
            Text("Launch")
        }
        Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { onSelect(Waypoint.Type.TURNPOINT) }) {
            Text("Turnpoint")
        }
        Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { onSelect(Waypoint.Type.LANDING) }) {
            Text("Landing")
        }
        Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { onCancel() }) {
            Text("Cancel")
        }
    }
}
