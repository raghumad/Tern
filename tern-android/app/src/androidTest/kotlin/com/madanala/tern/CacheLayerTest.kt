/**
 * CACHE LAYER TEST SPECIFICATIONS & IMPLEMENTATION
 * ================================================
 *
 * Comprehensive test suite for RouteCache layer covering:
 * - FlexBuffers serialization/deserialization
 * - Hilbert spatial indexing and queries
 * - Memory-mapped I/O performance
 * - Cache persistence and integrity
 * - Edge cases and error handling
 *
 * Expected Coverage Impact: ~15-20% of total codebase
 *
 * IMPLEMENTATION STATUS:
 * =====================
 * ✅ FlexBuffers Serialization/Deserialization Integrity
 * ✅ Empty Route and Edge Case Handling
 * ✅ Cache Statistics and Metadata Validation
 *
 * REMAINING TEST CATEGORIES TO IMPLEMENT:
 * ======================================
 *
 * Test 2: Hilbert Spatial Indexing and Query Accuracy
 * ---------------------------------------------------
 * - Create routes in different geographic regions (NYC, Boston, DC, Chicago)
 * - Cache all routes simultaneously
 * - Query routes within 50 miles of NYC center
 * - Verify only NYC routes returned, other cities excluded
 * - Test queries with different radii (50mi, 500mi, 2000mi)
 * - Validate distance-based filtering accuracy
 * - Test maximum route limits (queryNearbyRoutes with maxRoutes parameter)
 *
 * Test 3: Cache Persistence Across App Restarts
 * ---------------------------------------------
 * - Create and cache route with complex waypoint data
 * - Verify immediate cache availability
 * - Create new RouteCache instance (simulating app restart)
 * - Verify route still cached without re-caching
 * - Retrieve and validate complete data integrity
 * - Test cache index persistence on disk
 *
 * Test 4: Memory-Mapped I/O Zero-Copy Performance
 * -----------------------------------------------
 * - Create route with 20+ waypoints (stress test)
 * - Cache route and verify memory mapping establishment
 * - Perform 10 rapid retrievals of the same route
 * - Measure total retrieval time (< 100ms target)
 * - Validate data integrity after repeated access
 * - Confirm Hilbert curve ordering maintained in results
 *
 * Test 5: Cache Integrity Validation and Corruption Handling
 * ----------------------------------------------------------
 * - Cache route and verify initial integrity
 * - Test RouteCache.getCacheStats() returns valid statistics
 * - Clear specific route using clearCacheForRoute()
 * - Verify route removed from cache and statistics updated
 * - Test clearCache() removes all routes
 * - Validate cache file validation (size checks, readability)
 * - Test cache index integrity and recovery
 */

package com.madanala.tern

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.Waypoint
import com.madanala.tern.route.Route
import com.madanala.tern.utils.RouteCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Android Instrumentation Tests for RouteCache layer
 * Tests FlexBuffers serialization, Hilbert indexing, memory mapping, and cache integrity
 * Expected coverage impact: ~15-20% of total codebase
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CacheLayerTest {

    private lateinit var context: Context
    private lateinit var routeCache: RouteCache

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        routeCache = RouteCache(context)
        // Clear any existing cache data
        routeCache.clearCache()
    }

    @After
    fun tearDown() {
        routeCache.clearCache()
    }

    /**
     * Test 1: FlexBuffers Serialization/Deserialization Integrity
     * Ensures route data survives round-trip through cache storage
     */
    @Test
    fun testFlexBuffersSerializationDeserialization() = runTest {
        // Create test route with multiple waypoints spanning different coordinates
        val route = Route(
            id = "test_route_serialization",
            name = "Test Route Serialization",
            waypoints = listOf(
                Waypoint("wp1", 40.7128, -74.0060, Waypoint.Type.TURNPOINT, "WP1-1", Instant.now(), "test_route_serialization"),
                Waypoint("wp2", 40.7589, -73.9851, Waypoint.Type.TURNPOINT, "WP1-2", Instant.now(), "test_route_serialization"),
                Waypoint("wp3", 40.7505, -73.9934, Waypoint.Type.TURNPOINT, "WP1-3", Instant.now(), "test_route_serialization")
            )
        )

        // Cache the route
        routeCache.cacheRoute(route)

        // Verify route is marked as cached
        assertTrue("Route should be cached", routeCache.isCached(route.id))

        // Retrieve cached route
        val cachedRoute = routeCache.getCachedRoute(route.id)

        // Verify deserialization - route should not be null
        assertNotNull("Cached route should not be null", cachedRoute)

        // Verify all route metadata (id, name) and waypoint data integrity
        assertEquals("Route ID should match", route.id, cachedRoute?.id)
        assertEquals("Route name should match", route.name, cachedRoute?.name)
        assertEquals("Waypoint count should match", route.waypoints.size, cachedRoute?.waypoints?.size)

        // Confirm waypoint coordinates maintain precision (lat/lon to 4+ decimal places)
        cachedRoute?.waypoints?.forEachIndexed { index, waypoint ->
            val originalWaypoint = route.waypoints[index]
            assertEquals("Waypoint ID should match", originalWaypoint.id, waypoint.id)
            assertEquals("Waypoint latitude should match with precision", originalWaypoint.lat, waypoint.lat, 0.0001)
            assertEquals("Waypoint longitude should match with precision", originalWaypoint.lon, waypoint.lon, 0.0001)
            assertEquals("Waypoint type should match", originalWaypoint.type, waypoint.type)
            assertEquals("Waypoint label should match", originalWaypoint.label, waypoint.label)
            assertEquals("Waypoint route ID should match", route.id, waypoint.routeId)
        }
    }

    /**
     * Test Edge Case: Empty Route Handling
     */
    @Test
    fun testEmptyRouteHandling() = runTest {
        // Test caching empty route
        val emptyRoute = Route("empty_route", "Empty Route", emptyList())
        routeCache.cacheRoute(emptyRoute)

        // Empty route should not be cached or should handle gracefully
        val retrievedEmpty = routeCache.getCachedRoute(emptyRoute.id)
        // Current implementation may return null or empty route - this validates graceful handling
        assertFalse("Empty route should not be marked as cached", routeCache.isCached(emptyRoute.id))
    }

    /**
     * Test Edge Case: Single Waypoint Route
     */
    @Test
    fun testSingleWaypointRoute() = runTest {
        // Test route with single waypoint
        val singleWaypointRoute = Route(
            id = "single_wp_route",
            name = "Single Waypoint Route",
            waypoints = listOf(Waypoint("single", 40.0, -74.0, Waypoint.Type.TURNPOINT, "SINGLE", Instant.now(), "single_wp_route"))
        )

        routeCache.cacheRoute(singleWaypointRoute)
        assertTrue("Single waypoint route should be cached", routeCache.isCached(singleWaypointRoute.id))

        val retrievedSingle = routeCache.getCachedRoute(singleWaypointRoute.id)
        assertNotNull("Single waypoint route should be retrievable", retrievedSingle)
        assertEquals("Single waypoint route should have 1 waypoint", 1, retrievedSingle?.waypoints?.size)
        assertEquals("Waypoint data should match", singleWaypointRoute.waypoints[0].id, retrievedSingle?.waypoints?.get(0)?.id)
    }

    /**
     * Test Cache Statistics
     */
    @Test
    fun testCacheStatistics() = runTest {
        // Start with empty cache
        val initialStats = routeCache.getCacheStats()
        assertTrue("Initial cache should have statistics", initialStats.isNotEmpty())

        // Cache a route
        val route = Route(
            id = "stats_test_route",
            name = "Stats Test Route",
            waypoints = listOf(Waypoint("stats1", 40.0, -74.0, Waypoint.Type.TURNPOINT, "STATS-1", Instant.now(), "stats_test_route"))
        )
        routeCache.cacheRoute(route)

        // Test cache statistics with route
        val stats = routeCache.getCacheStats()
        assertTrue("Cache stats should be available", stats.containsKey("totalRoutes"))
        assertTrue("Cache stats should include size info", stats.containsKey("totalSizeBytes"))
        assertTrue("Should have at least 1 route", (stats["totalRoutes"] as? Int ?: 0) >= 1)
    }

    /**
     * Test Waypoint Type Persistence and Backward Compatibility
     * Ensures waypoint types are saved and restored correctly, including legacy routes without types
     */
    @Test
    fun testWaypointTypePersistenceAndBackwardCompatibility() = runTest {
        // Test route with different waypoint types
        val route = Route(
            id = "waypoint_types_test",
            name = "Waypoint Types Test",
            waypoints = listOf(
                Waypoint("launch", 40.7128, -74.0060, Waypoint.Type.LAUNCH, "LAUNCH", Instant.now(), "waypoint_types_test"),
                Waypoint("turnpoint", 40.7589, -73.9851, Waypoint.Type.TURNPOINT, "TURNPOINT", Instant.now(), "waypoint_types_test"),
                Waypoint("landing", 40.7505, -73.9934, Waypoint.Type.LANDING, "LANDING", Instant.now(), "waypoint_types_test")
            )
        )

        // Cache the route
        routeCache.cacheRoute(route)

        // Retrieve cached route
        val cachedRoute = routeCache.getCachedRoute(route.id)
        assertNotNull("Cached route should not be null", cachedRoute)

        // Verify all waypoint types are preserved
        assertEquals("Should have 3 waypoints", 3, cachedRoute?.waypoints?.size)
        val waypoints = cachedRoute?.waypoints?.sortedBy { it.id } ?: emptyList()

        assertEquals("First waypoint should be LAUNCH", Waypoint.Type.LAUNCH, waypoints[0].type)
        assertEquals("Second waypoint should be TURNPOINT", Waypoint.Type.TURNPOINT, waypoints[1].type)
        assertEquals("Third waypoint should be LANDING", Waypoint.Type.LANDING, waypoints[2].type)

        // Test backward compatibility by simulating legacy cache data
        // This tests the reconstructRouteFromFeatures method with missing waypointType
        val legacyFeatures = route.waypoints.map { waypoint ->
            val centroid = org.osmdroid.util.GeoPoint(waypoint.lat, waypoint.lon)
            val hilbertIndex = com.madanala.tern.utils.MapOverlayCacheUtils.computeHilbertIndex(centroid, 32)

            // Create feature WITHOUT waypointType (simulating legacy data)
            val featureData = mapOf(
                "type" to "Feature",
                "geometry" to mapOf(
                    "type" to "Point",
                    "coordinates" to listOf(waypoint.lon, waypoint.lat)
                ),
                "properties" to mapOf(
                    "waypointId" to waypoint.id,
                    "routeId" to route.id,
                    // NOTE: "waypointType" is intentionally omitted to test backward compatibility
                    "label" to (waypoint.label ?: ""),
                    "routeName" to route.name
                )
            )

            com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature(featureData, centroid, hilbertIndex, "route")
        }

        // Test reconstruction from legacy features (should default to TURNPOINT)
        val reconstructedRoute = (routeCache::class.java.getDeclaredMethod("reconstructRouteFromFeatures", String::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(routeCache, route.id, legacyFeatures)) as? Route

        assertNotNull("Reconstructed route should not be null", reconstructedRoute)
        assertEquals("Should have 3 waypoints", 3, reconstructedRoute?.waypoints?.size)

        // All waypoints should default to TURNPOINT for backward compatibility
        reconstructedRoute?.waypoints?.forEach { waypoint ->
            assertEquals("Legacy waypoint should default to TURNPOINT", Waypoint.Type.TURNPOINT, waypoint.type)
        }
    }
}
