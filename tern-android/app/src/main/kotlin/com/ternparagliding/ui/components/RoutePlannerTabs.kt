package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ternparagliding.model.Route
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore

/**
 * The SEARCH and LIBRARY tabs of the route planner (currently pilot-centric
 * mocks). Split out of RouteDetailPanel.kt (Phase 0c god-file split); same
 * ui.components package, so RouteDetailPanel's call sites are unchanged.
 */
@Composable
fun RouteSearchContent(store: MapStore) {
    var searchQuery by remember { mutableStateOf("") }

    // Pilot-Centric Search Mock
    val results = listOf(
        "Lookout Mountain Launch" to org.osmdroid.util.GeoPoint(39.7429, -105.2393),
        "Golden colorado" to org.osmdroid.util.GeoPoint(39.7526, -105.2201),
        "Boulder Flatirons" to org.osmdroid.util.GeoPoint(39.9880, -105.2930)
    )

    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search PG Spots (e.g. Golden)") },
            modifier = Modifier.fillMaxWidth().testTag("RouteSearchField"),
            singleLine = true,
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = null) },
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(results.filter { it.first.contains(searchQuery, ignoreCase = true) }) { _, (name, point) ->
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            store.dispatch(MapAction.LongPressMap(point, label = name))
                        }
                        .padding(vertical = 12.dp)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(androidx.compose.material.icons.Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("${"%.3f".format(point.latitude)}, ${"%.3f".format(point.longitude)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun RouteLibraryContent(store: MapStore) {
    // Mock Library Routes
    val libraryRoutes = listOf(
        Route(id = "lib_1", name = "Lookout Classic XC", waypoints = emptyList()),
        Route(id = "lib_2", name = "Boulder Canyon Run", waypoints = emptyList()),
        Route(id = "lib_3", name = "Golden Record Attempt", waypoints = emptyList())
    )

    Column {
        Text("SAVED ROUTES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(libraryRoutes) { _, route ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            store.dispatch(MapAction.AddRoute(route))
                            store.dispatch(MapAction.SelectRoute(route.id))
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.material.icons.Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(route.name, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
