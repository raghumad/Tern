package com.madanala.tern.ui.overlays

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayState
import com.madanala.tern.redux.WaypointSelection
import com.madanala.tern.model.Route
import com.madanala.tern.utils.DistanceZone
import com.madanala.tern.redux.OverlayType
import android.content.Context
import android.os.Looper
import io.mockk.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import com.madanala.tern.ui.overlays.OverlayCoordinator.OverlayAnimationManager

@OptIn(ExperimentalCoroutinesApi::class)
class RouteOverlayManagerTest {

private val testDispatcher = StandardTestDispatcher()

    private lateinit var manager: RouteOverlayManager
    private lateinit var mockMapStore: MapStore
    private lateinit var mockMapView: MapView
    private lateinit var mockCoordinator: OverlayCoordinator
    private lateinit var mockContext: Context
    private lateinit var mockRouteCache: com.madanala.tern.utils.RouteCache
    private lateinit var mockCacheDir: File
    private lateinit var mockLooper: Looper
    private lateinit var mockOverlays: MutableList<org.osmdroid.views.overlay.Overlay>

    @BeforeEach
    fun setUp() {
        // Set up test dispatcher for coroutines
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Looper::class)
        mockLooper = mockk(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper

        mockContext = mockk(relaxed = true)
        mockCacheDir = mockk(relaxed = true)
        every { mockContext.cacheDir } returns mockCacheDir
        every { mockContext.applicationContext } returns mockContext

        // Mock ActivityManager for AdaptiveOverlaySystem -> AndroidMemoryMonitor
        val mockActivityManager = mockk<android.app.ActivityManager>(relaxed = true)
        val mockMemoryInfo = android.app.ActivityManager.MemoryInfo()
        mockMemoryInfo.totalMem = 4L * 1024 * 1024 * 1024 // 4GB
        mockMemoryInfo.availMem = 2L * 1024 * 1024 * 1024 // 2GB
        mockMemoryInfo.lowMemory = false
        every { mockActivityManager.getMemoryInfo(any()) } answers {
            val outInfo = firstArg<android.app.ActivityManager.MemoryInfo>()
            outInfo.totalMem = mockMemoryInfo.totalMem
            outInfo.availMem = mockMemoryInfo.availMem
            outInfo.lowMemory = mockMemoryInfo.lowMemory
        }
        every { mockContext.getSystemService(Context.ACTIVITY_SERVICE) } returns mockActivityManager
        every { mockContext.getSystemService("activity") } returns mockActivityManager
        every { mockContext.getSystemService(android.app.ActivityManager::class.java) } returns mockActivityManager
        // Fallback for other services to avoid ClassCastException if accessed
        every { mockContext.getSystemService(neq("activity")) } returns null

        // Mock ContextCompat.getSystemService just in case
        mockkStatic(androidx.core.content.ContextCompat::class)
        every { androidx.core.content.ContextCompat.getSystemService(any(), android.app.ActivityManager::class.java) } returns mockActivityManager

        // Mock CacheManager singleton
        mockkObject(com.madanala.tern.utils.CacheManager)
        mockRouteCache = mockk(relaxed = true)
        every { com.madanala.tern.utils.CacheManager.routeCache } returns mockRouteCache
        // Mock the lazy property access or initialization check if needed
        every { com.madanala.tern.utils.CacheManager.initialize(any()) } just Runs

        // Mock android.os.Handler to handle Dispatchers.Main coroutine posting
        mockkStatic(android.os.Handler::class)
        mockkConstructor(android.os.Handler::class)
        every { anyConstructed<android.os.Handler>().post(any()) } answers {
            // Execute the Runnable immediately for testing
            val runnable = firstArg<Runnable>()
            runnable.run()
            true
        }
        every { anyConstructed<android.os.Handler>().postDelayed(any(), any()) } answers {
            // Execute the Runnable immediately for testing
            val runnable = firstArg<Runnable>()
            runnable.run()
            true
        }

        // Mock android.graphics.Paint to avoid Android-specific initialization issues
        mockkStatic("android.graphics.Paint")
        mockkConstructor(android.graphics.Paint::class)
        every { anyConstructed<android.graphics.Paint>().setColor(any()) } returns Unit
        every { anyConstructed<android.graphics.Paint>().color = any() } returns Unit
        every { anyConstructed<android.graphics.Paint>().strokeWidth = any() } returns Unit
        every { anyConstructed<android.graphics.Paint>().style = any() } returns Unit
        every { anyConstructed<android.graphics.Paint>().strokeCap = any() } returns Unit
        every { anyConstructed<android.graphics.Paint>().strokeJoin = any() } returns Unit
        every { anyConstructed<android.graphics.Paint>().isAntiAlias = any() } returns Unit
        every { anyConstructed<android.graphics.Paint>().textSize = any() } returns Unit
        every { anyConstructed<android.graphics.Paint>().textAlign = any() } returns Unit

        // Mock Android Log methods
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } answers {
            println("DEBUG: ${firstArg<String>()}: ${secondArg<String>()}")
            0
        }
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } answers {
            println("ERROR: ${firstArg<String>()}: ${secondArg<String>()}")
            0
        }
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } answers {
            println("ERROR: ${firstArg<String>()}: ${secondArg<String>()}")
            lastArg<Throwable>().printStackTrace()
            0
        }
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0


        mockMapStore = mockk(relaxed = true)
        mockMapView = mockk(relaxed = true)
        mockCoordinator = mockk(relaxed = true)
        val mockAnimManager = mockk<OverlayAnimationManager>(relaxed = true)
        every { mockCoordinator.getAnimationManager() } returns mockAnimManager
        
        // Mock animation manager to actually add overlays to the list
        every { mockAnimManager.animateOverlayAddition(any(), any(), any(), any(), any(), any()) } answers {
            val overlay = firstArg<org.osmdroid.views.overlay.Overlay>()
            mockOverlays.add(overlay)
        }
        every { mockAnimManager.animateOverlayRemoval(any(), any(), any(), any(), any()) } answers {
            val overlay = firstArg<org.osmdroid.views.overlay.Overlay>()
            mockOverlays.remove(overlay)
            val callback = lastArg<() -> Unit>()
            callback()
        }

        mockOverlays = mockk(relaxed = true)
        every { mockMapView.overlays } returns mockOverlays
        every { mockMapView.projection } returns mockk(relaxed = true)
        every { mockMapView.mapCenter } returns GeoPoint(0.0, 0.0)
        every { mockMapView.overlays } returns mockOverlays
        every { mockMapView.projection } returns mockk(relaxed = true)
        every { mockMapView.mapCenter } returns GeoPoint(40.0, -74.0)
        every { mockMapView.zoomLevelDouble } returns 10.0
        every { mockMapView.context } returns mockContext

        // Mock MapStore state
        val initialState = com.madanala.tern.redux.MapState()
        val mockStateFlow = io.mockk.mockk<kotlinx.coroutines.flow.StateFlow<com.madanala.tern.redux.MapState>>(relaxed = true)
        every { mockStateFlow.value } returns initialState
        every { mockMapStore.state } returns mockStateFlow
        
        // Pass mockContext to constructor
        manager = RouteOverlayManager(mockContext, mockMapStore)
        // Don't initialize here, let individual tests do it if they need specific setup
        manager.setOverlayCoordinator(mockCoordinator)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Looper::class)
        unmockkObject(com.madanala.tern.utils.CacheManager)
        manager.onDetach()
        clearAllMocks()
        // Reset the main dispatcher to the original Main dispatcher
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initialization creates manager with correct type`() {
        assertThat(manager.overlayType).isEqualTo(OverlayType.ROUTES)
    }

    @Test
    fun `onOverlayAttached loads persisted routes when store is empty`() {
        // Setup: Redux state is empty
        val emptyState = MapState(routes = emptyList())
        every { mockMapStore.state.value } returns emptyState
        
        // Setup: Cache has routes
        val cachedRoutes = listOf(
            Route(id = "cached1", waypoints = listOf(Waypoint(lat = 10.0, lon = 10.0))),
            Route(id = "cached2", waypoints = listOf(Waypoint(lat = 20.0, lon = 20.0)))
        )
        every { mockRouteCache.getAllCachedRoutes() } returns cachedRoutes

        // Act: Initialize the overlay (calls onAttach -> onOverlayAttached)
        manager.initialize(mockMapView)

        // Assert: Verify AddRoute actions are dispatched
        // Assert: Verify AddRoute actions are dispatched
        verify(exactly = 1) { 
            mockMapStore.dispatch(match<com.madanala.tern.redux.MapAction> { 
                it is com.madanala.tern.redux.MapAction.AddRoute && it.route.id == "cached1" 
            }) 
        }
        verify(exactly = 1) { 
            mockMapStore.dispatch(match<com.madanala.tern.redux.MapAction> { 
                it is com.madanala.tern.redux.MapAction.AddRoute && it.route.id == "cached2" 
            }) 
        }
    }

    @Test
    fun `redux state with routes enabled adds overlays`() {
        manager.initialize(mockMapView)
        val state = createStateWithRoutes(2, enabled = true)
        manager.onReduxStateChanged(state)

        // Verify map invalidation which happens after adding routes
        verify { mockMapView.invalidate() }
    }

    @Test
    fun `redux state with routes disabled clears overlays`() {
        manager.initialize(mockMapView)
        val enabledState = createStateWithRoutes(2, enabled = true)
        val disabledState = createStateWithRoutes(0, enabled = false)

        manager.onReduxStateChanged(enabledState)
        
        manager.onReduxStateChanged(disabledState)

        // The manager removes overlays individually via coordinator batch removal
        verify(exactly = 2) { 
            mockCoordinator.removeOverlayFromBatch(any(), any(), any(), any()) 
        }
        verify(exactly = 1) {
            mockCoordinator.removeOverlayFromBatch() // Commit batch
        }
    }

    @Test
    fun `performance stats include route metrics`() {
        manager.initialize(mockMapView)
        val state = createStateWithRoutes(3, enabled = true)
        manager.onReduxStateChanged(state)

        val stats = manager.getPerformanceStats()
        assertThat(stats["route_overlays_active"] as Int).isEqualTo(3)
        assertThat(stats["current_routes_count"] as Int).isEqualTo(3)
        assertThat(stats["overlay_coordinator_connected"] as Boolean).isTrue()
    }

    @Test
    fun `map move does NOT notify coordinator to avoid recursion`() {
        manager.initialize(mockMapView)
        val center = GeoPoint(40.0, -74.0)
        manager.performMapMove(center, 12.0)

        verify(exactly = 0) { mockCoordinator.onMapMoved(any(), any(), any()) }
    }

    @Test
    fun `viewport change notifies coordinator`() {
        manager.initialize(mockMapView)
        val viewport = BoundingBox(41.0, -73.0, 40.0, -75.0)
        manager.onViewportChanged(viewport)

        verify { mockCoordinator.onViewportChanged(viewport) }
    }

    @Test
    fun `distance zone budgets return valid values`() {
        manager.initialize(mockMapView)
        listOf(DistanceZone.CORE, DistanceZone.NEAR, DistanceZone.MID, DistanceZone.FAR, DistanceZone.EXTREME).forEach { zone ->
            val budget = manager.getZoneBudget(zone)
            assertThat(budget).isGreaterThan(-1)
        }
    }

    @Test
    fun `max overlays returns valid budget`() {
        manager.initialize(mockMapView)
        val maxOverlays = manager.getMaxOverlaysForCurrentConditions()
        assertThat(maxOverlays).isGreaterThan(0)
    }

    @Test
    fun `route cache stats return map`() {
        manager.initialize(mockMapView)
        val stats = manager.getRouteCacheStats()
        assertThat(stats).isNotNull()
    }



    private fun createStateWithRoutes(count: Int, enabled: Boolean = true): MapState {
        val routes = (1..count).map { 
            Route(id = "route$it", waypoints = listOf(
                Waypoint(lat = 40.0 + it, lon = -74.0 + it)
            ))
        }
        val overlayState = OverlayState(routes = OverlayConfig(enabled = enabled))
        return MapState(routes = routes, overlayState = overlayState)
    }
}