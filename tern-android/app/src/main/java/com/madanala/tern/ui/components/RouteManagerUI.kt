package com.madanala.tern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapStore
import com.madanala.tern.route.*
import kotlinx.coroutines.launch

/**
 * RouteManagerUI - Main interface for route management in Phase 1
 * Provides route-centric waypoint operations with proper UX validation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteManagerUI(
    modifier: Modifier = Modifier,
    mapStore: MapStore = viewModel()
) {
    val routeStore = RouteStore
    val waypointStore = WaypointStore

    val routes by routeStore.routes.collectAsState()
    val currentRoute by routeStore.currentRouteId.collectAsState()
    val waypoints by waypointStore.waypoints.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with route information
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Route Manager",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Current route information
                currentRoute?.let { routeId ->
                    val route = routes.find { it.id == routeId }
                    route?.let {
                        Text(
                            text = "Current Route: ${it.name}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Waypoints: ${it.waypoints.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } ?: Text(
                    text = "No active route",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Route list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(routes) { route ->
                RouteCard(
                    route = route,
                    isSelected = currentRoute == route.id,
                    onSelect = {
                        routeStore.setCurrentRoute(route.id)
                    },
                    onDelete = {
                        routeStore.remove(route.id)
                    },
                    waypointCount = route.waypoints.size
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    // Create new route
                    val newRoute = routeStore.createRoute("New Route")
                    routeStore.setCurrentRoute(newRoute.id)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("New Route")
            }

            Button(
                onClick = {
                    // Add test waypoint to current route
                    currentRoute?.let { routeId ->
                        val testWaypoint = waypointStore.createWaypointInRoute(
                            routeId = routeId,
                            lat = 46.5, // Example coordinates
                            lon = 6.8,
                            type = Waypoint.Type.TURNPOINT,
                            label = "Test WP ${System.currentTimeMillis()}"
                        )
                        routeStore.addWaypointToRoute(routeId, testWaypoint)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Add Test WP")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteCard(
    route: Route,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    waypointCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "$waypointCount waypoints • ${route.color.name}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row {
                if (!isSelected) {
                    OutlinedButton(onClick = onSelect) {
                        Text("Select")
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = onDelete) {
                    Text("×", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Preview composable for testing RouteManagerUI
 */
@androidx.compose.ui.tooling.preview.Preview
@Composable
fun RouteManagerUIPreview() {
    MaterialTheme {
        RouteManagerUI()
    }
}