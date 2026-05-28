package com.ternparagliding.utils

import android.content.Context
import android.util.Log
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.model.LocationType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.osmdroid.util.GeoPoint

class RouteCacheTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var routeCache: RouteCache
    private lateinit var mockDiskCache: SpatialDiskCache

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0

        context = mockk()
        every { context.cacheDir } returns tempDir
        
        // Mock SpatialDiskCache
        mockDiskCache = mockk<SpatialDiskCache>(relaxed = true)
        
        // Inject mock disk cache via constructor
        routeCache = RouteCache(context, mockDiskCache)
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

        // Stub getCachedFeatures to return a LineString feature representing this route
        val featureData = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "LineString",
                "coordinates" to listOf(listOf(20.0, 10.0))
            ),
            "properties" to mapOf(
                "routeId" to "test_route",
                "routeName" to "Test Route",
                "isVisible" to false,
                "waypoints" to listOf(
                    mapOf("id" to "wp1", "lat" to 10.0, "lon" to 20.0, "type" to "TURNPOINT", "label" to null)
                )
            )
        )
        val mockFeature = MapOverlayCacheUtils.OverlayFeature(
            internalId = null,
            feature = featureData, 
            centroid = GeoPoint(10.0, 20.0), 
            hilbertIndex = 0L, 
            overlayType = "route"
        )
        every { mockDiskCache.getCachedFeatures("test_route") } returns listOf(mockFeature)

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

        // Stub getCachedFeatures
        val featureData = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "LineString",
                "coordinates" to listOf(listOf(20.0, 10.0))
            ),
            "properties" to mapOf(
                "routeId" to "test_route_2",
                "routeName" to "Test Route 2",
                "isVisible" to true,
                "waypoints" to listOf(
                    mapOf("id" to "wp2", "lat" to 10.0, "lon" to 20.0, "type" to "TURNPOINT", "label" to null)
                )
            )
        )
        val mockFeature = MapOverlayCacheUtils.OverlayFeature(
            internalId = null,
            feature = featureData, 
            centroid = GeoPoint(10.0, 20.0), 
            hilbertIndex = 0L, 
            overlayType = "route"
        )
        every { mockDiskCache.getCachedFeatures("test_route_2") } returns listOf(mockFeature)

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

        // Stub getCachedFeatures
        val featureData = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "LineString",
                "coordinates" to listOf(listOf(20.0, 10.0), listOf(21.0, 11.0), listOf(22.0, 12.0))
            ),
            "properties" to mapOf(
                "routeId" to "test_route_order",
                "routeName" to "Ordered Route",
                "isVisible" to true,
                "waypoints" to listOf(
                    mapOf("id" to "wp1", "lat" to 10.0, "lon" to 20.0, "type" to "TURNPOINT", "label" to null),
                    mapOf("id" to "wp2", "lat" to 11.0, "lon" to 21.0, "type" to "TURNPOINT", "label" to null),
                    mapOf("id" to "wp3", "lat" to 12.0, "lon" to 22.0, "type" to "TURNPOINT", "label" to null)
                )
            )
        )
        val mockFeature = MapOverlayCacheUtils.OverlayFeature(
            internalId = null,
            feature = featureData, 
            centroid = GeoPoint(11.0, 21.0), 
            hilbertIndex = 0L, 
            overlayType = "route"
        )
        every { mockDiskCache.getCachedFeatures("test_route_order") } returns listOf(mockFeature)

        routeCache.cacheRoute(route)

        val cachedRoute = routeCache.getCachedRoute(route.id)
        assertTrue(cachedRoute != null, "Cached route should not be null")
        assertEquals(3, cachedRoute?.waypoints?.size)
        
        // Verify order is preserved
        assertEquals("wp1", cachedRoute?.waypoints?.get(0)?.id)
        assertEquals("wp2", cachedRoute?.waypoints?.get(1)?.id)
        assertEquals("wp3", cachedRoute?.waypoints?.get(2)?.id)
    }

    @Test
    fun `test cacheRoute converts to LineString feature`() {
        // Create a mock disk cache and inject it
        // Note: We are using the class-level mockDiskCache now, so no need to recreate
        
        val waypoints = listOf(
            Waypoint(id = "wp1", lat = 40.0, lon = -105.0, type = LocationType.LAUNCH),
            Waypoint(id = "wp2", lat = 40.1, lon = -105.1, type = LocationType.GOAL)
        )
        val route = Route(id = "route_ls", name = "LineString Route", waypoints = waypoints)

        routeCache.cacheRoute(route)

        // Verify that cacheFeatures was called
        io.mockk.verify { mockDiskCache.cacheFeatures(eq("route_ls"), any()) }
    }
    
    @Test
    fun `test getCachedRoute reconstructs from LineString feature`() {
        // Note: We are using the class-level mockDiskCache now, so no need to recreate
        
        val coordinates = listOf(
            listOf(-105.0, 40.0),
            listOf(-105.1, 40.1)
        )
        val waypointsMetadata = listOf(
            mapOf("id" to "wp1", "lat" to 40.0, "lon" to -105.0, "type" to "LAUNCH", "label" to "Launch"),
            mapOf("id" to "wp2", "lat" to 40.1, "lon" to -105.1, "type" to "GOAL", "label" to "Goal")
        )
        
        val featureData = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "LineString",
                "coordinates" to coordinates
            ),
            "properties" to mapOf(
                "routeId" to "route_recon",
                "routeName" to "Reconstructed Route",
                "waypoints" to waypointsMetadata
            )
        )
        
        val mockFeature = MapOverlayCacheUtils.OverlayFeature(
            feature = featureData,
            centroid = org.osmdroid.util.GeoPoint(40.05, -105.05),
            hilbertIndex = 12345L,
            overlayType = "route"
        )

        every { mockDiskCache.getCachedFeatures("route_recon") } returns listOf(mockFeature)

        val cachedRoute = routeCache.getCachedRoute("route_recon")
        
        assertTrue(cachedRoute != null)
        assertEquals("route_recon", cachedRoute?.id)
        assertEquals("Reconstructed Route", cachedRoute?.name)
        assertEquals(2, cachedRoute?.waypoints?.size)
        
        assertEquals("wp1", cachedRoute?.waypoints?.get(0)?.id)
        assertEquals(LocationType.LAUNCH, cachedRoute?.waypoints?.get(0)?.type)
        assertEquals("wp2", cachedRoute?.waypoints?.get(1)?.id)
        assertEquals(LocationType.GOAL, cachedRoute?.waypoints?.get(1)?.type)
    }
}
