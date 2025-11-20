package com.madanala.tern.ui.overlays

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayConfig
import com.madanala.tern.redux.OverlayState
import com.madanala.tern.redux.WaypointSelection
import com.madanala.tern.route.Route
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

class RouteOverlayManagerTest {

private val testDispatcher = StandardTestDispatcher()

private lateinit var manager: RouteOverlayManager
    private lateinit var mockMapStore: MapStore
    private lateinit var mockMapView: MapView
    private lateinit var mockCoordinator: OverlayCoordinator
    private lateinit var mockContext: Context
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
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0


        mockMapStore = mockk(relaxed = true)
        mockMapView = mockk(relaxed = true)
        mockCoordinator = mockk(relaxed = true)

        mockOverlays = mockk(relaxed = true)
        every { mockMapView.overlays } returns mockOverlays
        every { mockMapView.projection } returns mockk(relaxed = true)
        every { mockMapView.mapCenter } returns GeoPoint(0.0, 0.0)

        manager = RouteOverlayManager(mockMapStore)
        manager.initialize(mockMapView)
        manager.setOverlayCoordinator(mockCoordinator)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Looper::class)
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
    fun `redux state with routes enabled adds overlays`() {
        val state = createStateWithRoutes(2, enabled = true)
        manager.onReduxStateChanged(state)

        verify(exactly = 2) { mockOverlays.add(any()) }
        verify { mockMapView.invalidate() }
    }

    @Test
    fun `redux state with routes disabled clears overlays`() {
        val enabledState = createStateWithRoutes(2, enabled = true)
        val disabledState = createStateWithRoutes(0, enabled = false)

        manager.onReduxStateChanged(enabledState)
        manager.onReduxStateChanged(disabledState)

        // The manager removes overlays individually, it doesn't clear the whole map overlay list
        verify(exactly = 2) { mockOverlays.remove(any()) }
    }

    @Test
    fun `performance stats include route metrics`() {
        val state = createStateWithRoutes(3, enabled = true)
        manager.onReduxStateChanged(state)

        val stats = manager.getPerformanceStats()
        assertThat(stats["route_overlays_active"] as Int).isEqualTo(3)
        assertThat(stats["current_routes_count"] as Int).isEqualTo(3)
        assertThat(stats["overlay_coordinator_connected"] as Boolean).isTrue()
    }

    @Test
    fun `map move notifies coordinator`() {
        val center = GeoPoint(40.0, -74.0)
        manager.performMapMove(center, 12.0)

        verify { mockCoordinator.onMapMoved(40.0, -74.0, 12.0) }
    }

    @Test
    fun `viewport change notifies coordinator`() {
        val viewport = BoundingBox(41.0, -73.0, 40.0, -75.0)
        manager.onViewportChanged(viewport)

        verify { mockCoordinator.onViewportChanged(viewport) }
    }

    @Test
    fun `distance zone budgets return valid values`() {
        listOf(DistanceZone.CORE, DistanceZone.NEAR, DistanceZone.MID, DistanceZone.FAR, DistanceZone.EXTREME).forEach { zone ->
            val budget = manager.getZoneBudget(zone)
            assertThat(budget).isGreaterThan(-1)
        }
    }

    @Test
    fun `max overlays returns valid budget`() {
        val maxOverlays = manager.getMaxOverlaysForCurrentConditions()
        assertThat(maxOverlays).isGreaterThan(0)
    }

    @Test
    fun `route cache stats return map`() {
        val stats = manager.getRouteCacheStats()
        assertThat(stats).isNotNull()
    }

    @Test
    fun `getCurrentRoutes returns empty list initially`() {
        val routes = manager.getCurrentRoutes()
        assertThat(routes).isEmpty()
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