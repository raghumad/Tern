package com.madanala.tern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.R
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.RouteActions
import com.madanala.tern.redux.RouteExportFormat
import com.madanala.tern.redux.RouteState
import com.madanala.tern.route.Route
import com.madanala.tern.route.RouteColor
import kotlinx.coroutines.flow.StateFlow

/**
 * Comprehensive route management interface providing full route lifecycle management
 * Integrates with Redux state management and follows Material Design 3 patterns
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteManagerUI(
    onDismiss: () -> Unit,
    routeState: StateFlow<RouteState>,
    onAction: (RouteActions) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by routeState.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header with title and create button
            RouteManagerHeader(
                routeCount = state.routes.size,
                onCreateRoute = { onAction(RouteActions.CreateRoute("New Route")) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Route list
            if (state.routes.isEmpty()) {
                EmptyRoutesView(
                    onCreateRoute = { onAction(RouteActions.CreateRoute("New Route")) }
                )
            } else {
                RouteListView(
                    routes = state.routes,
                    currentRouteId = state.currentRouteId,
                    onRouteSelect = { routeId -> onAction(RouteActions.SetCurrentRoute(routeId)) },
                    onRouteVisibilityToggle = { routeId, visible ->
                        onAction(RouteActions.SetRouteVisibility(routeId, visible))
                    },
                    onRouteEdit = { routeId, newName ->
                        onAction(RouteActions.UpdateRouteMetadata(routeId, name = newName))
                    },
                    onRouteDelete = { routeId ->
                        onAction(RouteActions.RemoveRoute(routeId))
                    },
                    onRouteDuplicate = { route ->
                        val duplicatedRoute = route.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "${route.name} (Copy)",
                            createdAt = java.time.Instant.now(),
                            lastModified = java.time.Instant.now()
                        )
                        onAction(RouteActions.AddRoute(duplicatedRoute))
                    },
                    onRouteExport = { route, format ->
                        onAction(RouteActions.ExportRoute(route.id, format))
                    },
                    onWaypointMove = { fromRouteId, toRouteId, waypointId ->
                        // Handle waypoint movement between routes
                        val waypoint = state.routes.find { it.id == fromRouteId }
                            ?.waypoints?.find { it.id == waypointId }
                        if (waypoint != null) {
                            onAction(RouteActions.RemoveWaypointFromRoute(fromRouteId, waypointId))
                            onAction(RouteActions.AddWaypointToRoute(toRouteId, waypoint))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RouteManagerHeader(
    routeCount: Int,
    onCreateRoute: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Route Manager",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$routeCount route${if (routeCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FloatingActionButton(
            onClick = onCreateRoute,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Filled.Add, "Create Route")
        }
    }
}

@Composable
private fun EmptyRoutesView(
    onCreateRoute: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No routes yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create your first route to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(onClick = onCreateRoute) {
            Text("Create Route")
        }
    }
}

@Composable
private fun RouteListView(
    routes: List<Route>,
    currentRouteId: String?,
    onRouteSelect: (String) -> Unit,
    onRouteVisibilityToggle: (String, Boolean) -> Unit,
    onRouteEdit: (String, String) -> Unit,
    onRouteDelete: (String) -> Unit,
    onRouteDuplicate: (Route) -> Unit,
    onRouteExport: (Route, RouteExportFormat) -> Unit,
    onWaypointMove: (String, String, String) -> Unit
) {
    LazyColumn {
        items(routes) { route ->
            RouteCard(
                route = route,
                isSelected = route.id == currentRouteId,
                onSelect = { onRouteSelect(route.id) },
                onVisibilityToggle = { visible -> onRouteVisibilityToggle(route.id, visible) },
                onEdit = { newName -> onRouteEdit(route.id, newName) },
                onDelete = { onRouteDelete(route.id) },
                onDuplicate = { onRouteDuplicate(route) },
                onExport = { format -> onRouteExport(route, format) },
                onWaypointMove = { waypointId, targetRouteId ->
                    onWaypointMove(route.id, targetRouteId, waypointId)
                }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun RouteCard(
    route: Route,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onVisibilityToggle: (Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: (RouteExportFormat) -> Unit,
    onWaypointMove: (String, String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Route header with name and controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Route color indicator and name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(route.color.polylineColor))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = route.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${route.getWaypointCount()} waypoints • ${formatDistance(route.getTotalDistance())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Route controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Visibility toggle
                    IconButton(
                        onClick = { onVisibilityToggle(!route.isVisible) }
                    ) {
                        Icon(
                            if (route.isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (route.isVisible) "Hide route" else "Show route"
                        )
                    }

                    // Menu button
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, "Route options")
                    }
                }
            }

            // Expanded waypoint list for selected route
            if (isSelected && route.waypoints.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                WaypointList(
                    waypoints = route.waypoints,
                    route = route,
                    onWaypointMove = onWaypointMove
                )
            }

            // Dropdown menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Filled.Edit, "Edit") },
                    onClick = {
                        showMenu = false
                        showEditDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, "Duplicate") },
                    onClick = {
                        showMenu = false
                        onDuplicate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Export") },
                    leadingIcon = { Icon(Icons.Filled.FileDownload, "Export") },
                    onClick = {
                        showMenu = false
                        showExportDialog = true
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }

    // Edit dialog
    if (showEditDialog) {
        RouteEditDialog(
            currentName = route.name,
            onConfirm = { newName ->
                onEdit(newName)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false }
        )
    }

    // Export dialog
    if (showExportDialog) {
        RouteExportDialog(
            routeName = route.name,
            onExport = { format ->
                onExport(format)
                showExportDialog = false
            },
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun WaypointList(
    waypoints: List<Waypoint>,
    route: Route,
    onWaypointMove: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Waypoints",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            waypoints.forEachIndexed { index, waypoint ->
                WaypointItem(
                    waypoint = waypoint,
                    index = index,
                    route = route,
                    onWaypointMove = onWaypointMove
                )
            }
        }
    }
}

@Composable
private fun WaypointItem(
    waypoint: Waypoint,
    index: Int,
    route: Route,
    onWaypointMove: (String, String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Waypoint type icon
        Box(
            modifier = Modifier
                .size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (waypoint.type) {
                    Waypoint.Type.LAUNCH -> "🚀"
                    Waypoint.Type.LANDING -> "🎯"
                    Waypoint.Type.TURNPOINT -> "⭕"
                },
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = waypoint.label ?: "Waypoint ${index + 1}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Lat: ${waypoint.lat.format(4)}, Lon: ${waypoint.lon.format(4)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Drag handle (placeholder for drag & drop)
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RouteEditDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Route Name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Route name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RouteExportDialog(
    routeName: String,
    onExport: (RouteExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Route") },
        text = {
            Column {
                Text("Export \"$routeName\" in format:")
                Spacer(modifier = Modifier.height(16.dp))
                RouteExportFormat.values().forEach { format ->
                    TextButton(
                        onClick = { onExport(format) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = format.name,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Helper function to format distance
private fun formatDistance(distance: Double): String {
    return if (distance > 0) {
        "${distance.format(1)} km"
    } else {
        "0 km"
    }
}

// Extension function to format numbers
private fun Double.format(digits: Int) = "%.${digits}f".format(this)