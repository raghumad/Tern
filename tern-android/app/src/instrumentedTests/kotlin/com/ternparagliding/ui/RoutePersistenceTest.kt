package com.ternparagliding.ui
import com.ternparagliding.model.LocationType

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.utils.CacheManager
import com.ternparagliding.utils.RouteCache
import com.ternparagliding.utils.MapVisualTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoutePersistenceTest : MapVisualTest() {

    private lateinit var routeCache: RouteCache
    private lateinit var testRoute: Route

    @Before
    fun initCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Initialize CacheManager with context (required for RouteCache)
        com.ternparagliding.utils.ReportGenerator.logStep("SETUP", "Initializing CacheManager and RouteCache")
        CacheManager.initialize(context)
        routeCache = CacheManager.routeCache
        
        // Ensure clean state
        com.ternparagliding.utils.ReportGenerator.logStep("SETUP", "Clearing all caches")
        routeCache.clearCache()
    }

    @Test
    fun testRoutePersistence() = scenario("Route Persistence") {
        story("As a pilot who meticulously plans flights at home, I want my routes to be reliably saved to disk so that they are immediately available when I arrive at the launch site, even without an internet connection.") {
            val routeId = UUID.randomUUID().toString()
            
            given("I have carefully crafted a flight plan for my next adventure") {
                testRoute = Route(
                    id = routeId,
                    name = "Test Persistence Route",
                    waypoints = listOf(
                        Waypoint(lat = 40.0, lon = -105.0, type = LocationType.LAUNCH, label = "Launch"),
                        Waypoint(lat = 40.1, lon = -105.1, type = LocationType.TURNPOINT, label = "Turn 1"),
                        Waypoint(lat = 40.2, lon = -105.2, type = LocationType.LANDING, label = "Goal")
                    )
                )
            }

            `when`("I save this mission to the persistent storage (cache)") {
                com.ternparagliding.utils.ReportGenerator.logStep("ACTION", "Caching route: ${testRoute.name}")
                routeCache.cacheRoute(testRoute)
            }

            this.then("my route should be immediately retrievable from the offline database") {
                assertTrue("Route should be marked as cached", routeCache.isCached(testRoute.id))
                
                val cachedRoute = routeCache.getCachedRoute(testRoute.id)
                assertNotNull("Cached route should not be null", cachedRoute)
                assertEquals("Route name should match", testRoute.name, cachedRoute?.name)
                assertEquals("Waypoint count should match", testRoute.waypoints.size, cachedRoute?.waypoints?.size)
                assertEquals("First waypoint label should match", "Launch", cachedRoute?.waypoints?.first()?.label)
            }

            and("the underlying FlexBuffer and index files must exist on the device storage") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val cacheDir = File(context.cacheDir, "route_cache")
                val flexFile = File(cacheDir, "${testRoute.id.uppercase()}_route.flex")
                val idxFile = File(cacheDir, "${testRoute.id.uppercase()}_route.idx")

                assertTrue("FlexBuffer file should exist", flexFile.exists())
                assertTrue("Index file should exist", idxFile.exists())
                assertTrue("FlexBuffer file should not be empty", flexFile.length() > 0)
            }
            
            and("my entire collection of offline missions should include this new flight plan") {
                val allRoutes = routeCache.getAllCachedRoutes()
                assertTrue("All routes should contain the new route", allRoutes.any { it.id.equals(testRoute.id, ignoreCase = true) })
            }

            and("The database operations are performed without blocking the main thread or causing visual stutters") {
                com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "STATE_UPDATE_STORM")
                com.ternparagliding.utils.ReportGenerator.assertLogDoesNotContain("PerformanceDebugger", "VISUAL_DISCONTINUITY")
            }
        }
    }
}
