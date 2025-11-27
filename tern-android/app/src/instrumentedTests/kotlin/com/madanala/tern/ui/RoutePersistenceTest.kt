package com.madanala.tern.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import com.madanala.tern.utils.BddTest
import com.madanala.tern.utils.CacheManager
import com.madanala.tern.utils.RouteCache
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoutePersistenceTest : BddTest() {

    private lateinit var routeCache: RouteCache
    private lateinit var testRoute: Route

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Initialize CacheManager with context (required for RouteCache)
        com.madanala.tern.utils.ReportGenerator.logStep("SETUP", "Initializing CacheManager and RouteCache")
        CacheManager.initialize(context)
        routeCache = CacheManager.routeCache
        
        // Ensure clean state
        com.madanala.tern.utils.ReportGenerator.logStep("SETUP", "Clearing all caches")
        routeCache.clearCache()
    }

    @Test
    fun testRoutePersistence() = scenario("Route Persistence") {
        val routeId = UUID.randomUUID().toString()
        
        given("a new route with waypoints") {
            testRoute = Route(
                id = routeId,
                name = "Test Persistence Route",
                waypoints = listOf(
                    Waypoint(lat = 40.0, lon = -105.0, type = Waypoint.Type.LAUNCH, label = "Launch"),
                    Waypoint(lat = 40.1, lon = -105.1, type = Waypoint.Type.TURNPOINT, label = "Turn 1"),
                    Waypoint(lat = 40.2, lon = -105.2, type = Waypoint.Type.LANDING, label = "Goal")
                )
            )
        }

        `when`("the route is cached") {
            com.madanala.tern.utils.ReportGenerator.logStep("ACTION", "Caching route: ${testRoute.name}")
            routeCache.cacheRoute(testRoute)
        }

        then("the route should be retrieved from cache") {
            assertTrue("Route should be marked as cached", routeCache.isCached(testRoute.id))
            
            val cachedRoute = routeCache.getCachedRoute(testRoute.id)
            assertNotNull("Cached route should not be null", cachedRoute)
            assertEquals("Route name should match", testRoute.name, cachedRoute?.name)
            assertEquals("Waypoint count should match", testRoute.waypoints.size, cachedRoute?.waypoints?.size)
            assertEquals("First waypoint label should match", "Launch", cachedRoute?.waypoints?.first()?.label)
        }

        then("the cache files should exist on disk") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val cacheDir = File(context.cacheDir, "route_cache")
            val flexFile = File(cacheDir, "${testRoute.id}_route.flex")
            val idxFile = File(cacheDir, "${testRoute.id}_route.idx")

            assertTrue("FlexBuffer file should exist", flexFile.exists())
            assertTrue("Index file should exist", idxFile.exists())
            assertTrue("FlexBuffer file should not be empty", flexFile.length() > 0)
        }
        
        then("getAllCachedRoutes should include the new route") {
            val allRoutes = routeCache.getAllCachedRoutes()
            assertTrue("All routes should contain the new route", allRoutes.any { it.id == testRoute.id })
        }
    }
}
