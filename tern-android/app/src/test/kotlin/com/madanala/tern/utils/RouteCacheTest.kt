package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import com.madanala.tern.model.Route
import com.madanala.tern.model.Waypoint
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RouteCacheTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var routeCache: RouteCache

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0

        context = mockk()
        every { context.cacheDir } returns tempDir
        routeCache = RouteCache(context)
    }

    @Test
    fun `test cache and retrieve route with isVisible`() {
        // Create a route with a waypoint (required for caching)
        val waypoint = Waypoint(lat = 10.0, lon = 20.0, routeId = "test_route")
        val route = Route(
            id = "test_route",
            name = "Test Route",
            waypoints = listOf(waypoint),
            isVisible = false
        )

        routeCache.cacheRoute(route)

        val cachedRoute = routeCache.getCachedRoute(route.id)
        assertTrue(cachedRoute != null, "Cached route should not be null")
        assertEquals(false, cachedRoute?.isVisible, "isVisible should be false")
        assertEquals("Test Route", cachedRoute?.name)
    }

    @Test
    fun `test cache and retrieve route with isVisible true`() {
        val waypoint = Waypoint(lat = 10.0, lon = 20.0, routeId = "test_route_2")
        val route = Route(
            id = "test_route_2",
            name = "Test Route 2",
            waypoints = listOf(waypoint),
            isVisible = true
        )

        routeCache.cacheRoute(route)

        val cachedRoute = routeCache.getCachedRoute(route.id)
        assertTrue(cachedRoute != null, "Cached route should not be null")
        assertEquals(true, cachedRoute?.isVisible, "isVisible should be true")
    }
    @Test
    fun `test cache and retrieve route preserves waypoint order`() {
        // Create a route with multiple waypoints in a specific order
        val waypoint1 = Waypoint(id = "wp1", lat = 10.0, lon = 20.0, routeId = "test_route_order")
        val waypoint2 = Waypoint(id = "wp2", lat = 11.0, lon = 21.0, routeId = "test_route_order")
        val waypoint3 = Waypoint(id = "wp3", lat = 12.0, lon = 22.0, routeId = "test_route_order")
        
        val route = Route(
            id = "test_route_order",
            name = "Ordered Route",
            waypoints = listOf(waypoint1, waypoint2, waypoint3)
        )

        routeCache.cacheRoute(route)

        val cachedRoute = routeCache.getCachedRoute(route.id)
        assertTrue(cachedRoute != null, "Cached route should not be null")
        assertEquals(3, cachedRoute?.waypoints?.size)
        
        // Verify order is preserved
        assertEquals("wp1", cachedRoute?.waypoints?.get(0)?.id)
        assertEquals("wp2", cachedRoute?.waypoints?.get(1)?.id)
        assertEquals("wp3", cachedRoute?.waypoints?.get(2)?.id)
    }
}
