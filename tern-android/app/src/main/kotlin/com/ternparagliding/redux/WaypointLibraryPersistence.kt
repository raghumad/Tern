package com.ternparagliding.redux

import com.ternparagliding.utils.cache.WaypointLibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Persists the standalone **waypoint library** to disk and hydrates it on start.
 * Mirrors [TaskPersistence]'s observer pattern (observe post-reduce state), but the
 * library is a flat set so it's a single JSON write via [WaypointLibraryStore].
 */
object WaypointLibraryPersistence {

    /** Hydrate from disk once, then write the library through on every change.
     *  Suspends forever — launch from a store-scoped coroutine. */
    suspend fun observe(store: MapStore, libraryStore: WaypointLibraryStore) {
        val saved = withContext(Dispatchers.IO) { libraryStore.load() }
        if (saved.isNotEmpty()) store.dispatch(MapAction.SetWaypointLibrary(saved))

        store.state
            .map { it.waypointLibrary }
            .distinctUntilChanged()
            .collect { library ->
                withContext(Dispatchers.IO) { libraryStore.save(library) }
            }
    }
}
