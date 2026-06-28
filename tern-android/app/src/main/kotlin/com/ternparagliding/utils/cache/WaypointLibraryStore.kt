package com.ternparagliding.utils.cache

import android.content.Context
import android.util.Log
import com.ternparagliding.model.LibraryWaypoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Flat JSON persistence for the standalone **waypoint library**. Unlike the
 * spatial [TaskCache], the library is a small flat set keyed by code, so a single
 * JSON file is the right tool — simple, atomic, easy to inspect.
 */
class WaypointLibraryStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(LibraryWaypoint.serializer())

    fun load(): List<LibraryWaypoint> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "library load failed", e); emptyList()
    }

    fun save(waypoints: List<LibraryWaypoint>) {
        try {
            file.writeText(json.encodeToString(serializer, waypoints))
        } catch (e: Exception) {
            Log.e(TAG, "library save failed", e)
        }
    }

    companion object {
        private const val FILE_NAME = "waypoint_library.json"
        private const val TAG = "WaypointLibraryStore"
    }
}
