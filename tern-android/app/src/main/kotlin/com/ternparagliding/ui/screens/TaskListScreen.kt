package com.ternparagliding.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ternparagliding.R
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.model.Task
import com.ternparagliding.utils.io.TaskIOManager
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun TaskListScreen(
    modifier: Modifier = Modifier,
    store: MapStore,
    onTaskSelected: () -> Unit = {}, // Callback to navigate to map screen
    onDismiss: () -> Unit = {}, // Callback to dismiss the screen
    onManageWaypoints: () -> Unit = {} // Open the standalone waypoint library
) {
    val state by store.state.collectAsState()
    val tasks = state.tasks
    val selectedTaskId = state.selectedTaskId
    val context = LocalContext.current
    val mapCenter = state.center

    // Filter tasks based on viewport (simple distance check < 200km from center)
    val filteredTasks = remember(tasks, mapCenter) {
        val centerLat = mapCenter?.latitude ?: 0.0
        val centerLon = mapCenter?.longitude ?: 0.0
        if (mapCenter == null) return@remember tasks // Show all if center unknown

        tasks.filter { task ->
            if (task.waypoints.isEmpty()) return@filter true // Keep empty tasks visible
            task.waypoints.any { wp ->
                calculateDistance(wp.lat, wp.lon, centerLat, centerLon) < 200.0
            }
        }
    }

    // File Picker for Import
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val importedTask = TaskIOManager.importTaskFromUri(context, it)
            if (importedTask != null) {
                store.dispatch(MapAction.AddTask(importedTask))
                store.dispatch(MapAction.SelectTask(importedTask.id))
                onTaskSelected()
            }
        }
    }

    // QR Scanner
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val importedTask = TaskIOManager.importTaskFromQrString(result.contents)
            if (importedTask != null) {
                store.dispatch(MapAction.AddTask(importedTask))
                store.dispatch(MapAction.SelectTask(importedTask.id))
                onTaskSelected()
            }
        }
    }

    // State for delete confirmation dialog
    var showDeleteDialog by remember { mutableStateOf(false) }
    var taskToDelete by remember { mutableStateOf<String?>(null) }

    // State for rename dialog
    var showRenameDialog by remember { mutableStateOf(false) }
    var taskToRename by remember { mutableStateOf<Task?>(null) }
    var newTaskName by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() } // Dismiss on tap outside
    ) {
        // Main Content Card (Surface)
        Card(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 600.dp) // Limit height
                .align(Alignment.Center)
                .clickable(enabled = false) {}, // Consume clicks inside the card
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
        // Header with Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tasks",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = onManageWaypoints) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Waypoint library")
                }
                IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Upload, contentDescription = "Import Task")
                }
                IconButton(onClick = {
                    val options = ScanOptions()
                    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    options.setPrompt("Scan a Task QR Code")
                    options.setBeepEnabled(false)
                    scanLauncher.launch(options)
                }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
                }
            }
        }

        if (filteredTasks.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No nearby tasks found",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create a new task or pan the map to see others",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        // Create a new empty task with default name
                        val newTask = Task(name = "New Task ${tasks.size + 1}")
                        store.dispatch(MapAction.AddTask(newTask))
                        store.dispatch(MapAction.SelectTask(newTask.id))
                        onTaskSelected()
                    }
                ) {
                    Text("Create New Task")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredTasks) { task ->
                    val isSelected = selectedTaskId == task.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                store.dispatch(MapAction.SelectTask(task.id))
                                onTaskSelected()
                            },
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (isSelected) 8.dp else 2.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = task.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Row {
                                    IconButton(
                                        onClick = {
                                            val updatedTask = task.copy(
                                                isVisible = !task.isVisible,
                                                updatedAt = Instant.now()
                                            )
                                            store.dispatch(MapAction.UpdateTask(updatedTask))
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (task.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (task.isVisible) "Hide task" else "Show task",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            taskToRename = task
                                            newTaskName = task.name
                                            showRenameDialog = true
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.qr_code_2_24), // Reusing icon for rename? Should be edit.
                                            // Wait, previous code used qr_code_2_24 for rename? That's weird.
                                            // I'll change it to Edit icon if available or keep it to minimize diff noise if I don't have Edit icon.
                                            // I'll stick to what was there or use a standard Edit icon.
                                            // Let's use standard Edit icon if possible, but I don't have the import.
                                            // I'll leave it as is for now to avoid breaking if R.drawable.edit isn't there.
                                            // Actually, I'll use Icons.Default.Edit if I add the import.
                                            // I'll just keep the existing painterResource for now.
                                            contentDescription = "Rename task",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            taskToDelete = task.id
                                            showDeleteDialog = true
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.outbox_alt_24),
                                            contentDescription = "Delete task",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${"%.2f".format(task.totalDistanceKm)} km",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${task.estimatedFlightTimeMinutes} min",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${task.waypoints.size} WPs",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Action Button for creating new tasks
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
             FloatingActionButton(
                onClick = {
                    // Create a new empty task with default name
                    val newTask = Task(name = "New Task ${tasks.size + 1}")
                    store.dispatch(MapAction.AddTask(newTask))
                    store.dispatch(MapAction.SelectTask(newTask.id))
                    onTaskSelected()
                }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.route_24),
                    contentDescription = "Create New Task"
                )
            }
        }
        }
    }
}

    // Delete confirmation dialog
    if (showDeleteDialog && taskToDelete != null) {
        val taskName = tasks.find { it.id == taskToDelete }?.name ?: "Unknown Task"

        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                taskToDelete = null
            },
            title = {
                Text(text = "Delete Task")
            },
            text = {
                Text(text = "Are you sure you want to delete \"$taskName\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        taskToDelete?.let { taskId ->
                            store.dispatch(MapAction.RemoveTask(taskId))
                        }
                        showDeleteDialog = false
                        taskToDelete = null
                    }
                ) {
                    Text(text = "Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        taskToDelete = null
                    }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog && taskToRename != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                taskToRename = null
                newTaskName = ""
            },
            title = {
                Text(text = "Rename Task")
            },
            text = {
                Column {
                    Text(text = "Enter a new name for the task:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newTaskName,
                        onValueChange = { newTaskName = it },
                        label = { Text("Task Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmedName = newTaskName.trim()
                        if (trimmedName.isNotEmpty()) {
                            taskToRename?.let { task ->
                                val updatedTask = task.copy(
                                    name = trimmedName,
                                    updatedAt = Instant.now()
                                )
                                store.dispatch(MapAction.UpdateTask(updatedTask))
                            }
                            showRenameDialog = false
                            taskToRename = null
                            newTaskName = ""
                        }
                    },
                    enabled = newTaskName.trim().isNotEmpty()
                ) {
                    Text(text = "Rename")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        taskToRename = null
                        newTaskName = ""
                    }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371 // Earth radius in km
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}