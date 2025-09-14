package com.madanala.tern.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.madanala.tern.R
import com.madanala.tern.ui.screens.MAP_VIEW_SATELLITE
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    onUpdateMapStyle: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Map Style", style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = {
                    onUpdateMapStyle(MAP_VIEW_TERRAIN)
                    onDismiss()
                }) {
                    Text("Terrain")
                }
                Button(onClick = {
                    onUpdateMapStyle(MAP_VIEW_SATELLITE)
                    onDismiss()
                }) {
                    Text("Satellite")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Share", style = MaterialTheme.typography.titleLarge)
            ShareOption(icon = R.drawable.file_save_24, text = "Save")
            ShareOption(icon = R.drawable.outbox_alt_24, text = "Share .xctsk File")
            ShareOption(icon = R.drawable.qr_code_2_24, text = "Share QR Code")
            ShareOption(icon = R.drawable.local_cafe_24, text = "Share .cup File")
            ShareOption(icon = R.drawable.route_24, text = "Share .wpt (CompeGPS)")
        }
    }
}

@Composable
private fun ShareOption(icon: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(id = icon), contentDescription = text, modifier = Modifier.padding(end = 16.dp))
        Text(text)
    }
}
