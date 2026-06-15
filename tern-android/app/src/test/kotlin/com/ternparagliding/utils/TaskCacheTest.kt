package com.ternparagliding.utils
import com.ternparagliding.utils.cache.MapOverlayCacheUtils
import com.ternparagliding.utils.cache.TaskCache
import com.ternparagliding.utils.cache.SpatialDiskCache

import android.content.Context
import android.util.Log
import com.ternparagliding.model.Task
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

class TaskCacheTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var taskCache: TaskCache
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
        taskCache = TaskCache(context, mockDiskCache)
    }

    @Test
    fun `test cache and retrieve task with isVisible`() {
        // Create a task with a waypoint (required for caching)
        val waypoint = Waypoint(lat = 10.0, lon = 20.0, taskId = "test_task")
        val task = Task(
            id = "test_task",
            name = "Test Task",
            waypoints = listOf(waypoint),
            isVisible = false
        )

        // Stub getCachedFeatures to return a LineString feature representing this task
        val featureData = mapOf(
            "type" to "Feature",
            "geometry" to mapOf(
                "type" to "LineString",
                "coordinates" to listOf(listOf(20.0, 10.0))
            ),
            "properties" to mapOf(
                "taskId" to "test_task",
                "taskName" to "Test Task",
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
            overlayType = "task"
        )
        every { mockDiskCache.getCachedFeatures("test_task") } returns listOf(mockFeature)

        taskCache.cacheTask(task)

        val cachedTask = taskCache.getCachedTask(task.id)
        assertTrue(cachedTask != null, "Cached task should not be null")
        assertEquals(false, cachedTask?.isVisible, "isVisible should be false")
        assertEquals("Test Task", cachedTask?.name)
    }

    @Test
    fun `test cache and retrieve task with isVisible true`() {
        val waypoint = Waypoint(lat = 10.0, lon = 20.0, taskId = "test_task_2")
        val task = Task(
            id = "test_task_2",
            name = "Test Task 2",
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
                "taskId" to "test_task_2",
                "taskName" to "Test Task 2",
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
            overlayType = "task"
        )
        every { mockDiskCache.getCachedFeatures("test_task_2") } returns listOf(mockFeature)

        taskCache.cacheTask(task)

        val cachedTask = taskCache.getCachedTask(task.id)
        assertTrue(cachedTask != null, "Cached task should not be null")
        assertEquals(true, cachedTask?.isVisible, "isVisible should be true")
    }
    @Test
    fun `test cache and retrieve task preserves waypoint order`() {
        // Create a task with multiple waypoints in a specific order
        val waypoint1 = Waypoint(id = "wp1", lat = 10.0, lon = 20.0, taskId = "test_task_order")
        val waypoint2 = Waypoint(id = "wp2", lat = 11.0, lon = 21.0, taskId = "test_task_order")
        val waypoint3 = Waypoint(id = "wp3", lat = 12.0, lon = 22.0, taskId = "test_task_order")
        
        val task = Task(
            id = "test_task_order",
            name = "Ordered Task",
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
                "taskId" to "test_task_order",
                "taskName" to "Ordered Task",
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
            overlayType = "task"
        )
        every { mockDiskCache.getCachedFeatures("test_task_order") } returns listOf(mockFeature)

        taskCache.cacheTask(task)

        val cachedTask = taskCache.getCachedTask(task.id)
        assertTrue(cachedTask != null, "Cached task should not be null")
        assertEquals(3, cachedTask?.waypoints?.size)
        
        // Verify order is preserved
        assertEquals("wp1", cachedTask?.waypoints?.get(0)?.id)
        assertEquals("wp2", cachedTask?.waypoints?.get(1)?.id)
        assertEquals("wp3", cachedTask?.waypoints?.get(2)?.id)
    }

    @Test
    fun `test cacheTask converts to LineString feature`() {
        // Create a mock disk cache and inject it
        // Note: We are using the class-level mockDiskCache now, so no need to recreate
        
        val waypoints = listOf(
            Waypoint(id = "wp1", lat = 40.0, lon = -105.0, type = LocationType.LAUNCH),
            Waypoint(id = "wp2", lat = 40.1, lon = -105.1, type = LocationType.GOAL)
        )
        val task = Task(id = "task_ls", name = "LineString Task", waypoints = waypoints)

        taskCache.cacheTask(task)

        // Verify that cacheFeatures was called
        io.mockk.verify { mockDiskCache.cacheFeatures(eq("task_ls"), any()) }
    }
    
    @Test
    fun `test getCachedTask reconstructs from LineString feature`() {
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
                "taskId" to "task_recon",
                "taskName" to "Reconstructed Task",
                "waypoints" to waypointsMetadata
            )
        )
        
        val mockFeature = MapOverlayCacheUtils.OverlayFeature(
            feature = featureData,
            centroid = org.osmdroid.util.GeoPoint(40.05, -105.05),
            hilbertIndex = 12345L,
            overlayType = "task"
        )

        every { mockDiskCache.getCachedFeatures("task_recon") } returns listOf(mockFeature)

        val cachedTask = taskCache.getCachedTask("task_recon")
        
        assertTrue(cachedTask != null)
        assertEquals("task_recon", cachedTask?.id)
        assertEquals("Reconstructed Task", cachedTask?.name)
        assertEquals(2, cachedTask?.waypoints?.size)
        
        assertEquals("wp1", cachedTask?.waypoints?.get(0)?.id)
        assertEquals(LocationType.LAUNCH, cachedTask?.waypoints?.get(0)?.type)
        assertEquals("wp2", cachedTask?.waypoints?.get(1)?.id)
        assertEquals(LocationType.GOAL, cachedTask?.waypoints?.get(1)?.type)
    }
}
