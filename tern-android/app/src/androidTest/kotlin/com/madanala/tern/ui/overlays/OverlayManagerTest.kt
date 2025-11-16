package com.madanala.tern.ui.overlays

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madanala.tern.model.Waypoint
import com.madanala.tern.redux.*
import com.madanala.tern.route.Route
import org.junit.*
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.time.Instant

/**
 * Android Instrumentation Tests for RouteOverlayManager
 * Tests overlay lifecycle, Redux integration, and waypoint interaction handling
 * Critical integration testing for aviation UI components
 * Coverage targets: +10-15% overall coverage through integration validation
 */
@RunWith(AndroidJUnit4::class)
class OverlayManagerTest {

    private lateinit var context: Context
    private lateinit var mapView: MapView
    private lateinit var reduxStore: MapStore
    private lateinit var overlayManager: RouteOverlayManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Create MapView for testing overlay integration
        mapView = MapView(context).apply {
            id = android.R.id.content // Assign proper ID for Android testing
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(40.7128, -74.0060))
        }

        // Initialize Redux store
        reduxStore = MapStore()

        // Create and initialize RouteOverlayManager
        overlayManager = RouteOverlayManager(reduxStore)
        overlayManager.initialize(mapView)
        overlayManager.onAttach(mapView)
    }

    @After
    fun tearDown() {
        // Clean up overlay manager and map view
        overlayManager.onDetach()
        mapView.onDetach()
    }

    @Test
    fun testOverlayManagerInitialization() {
        // Test that overlay manager initializes correctly with Android context
        Assert.assertEquals("Overlay type should be ROUTES", OverlayType.ROUTES, overlayManager.overlayType)

        val config = overlayManager.getCurrentConfig()
        Assert.assertNotNull("Config should be available", config)

        // Test that overlay manager can be enabled/disabled properly
        overlayManager.setEnabled(true)
        var configAfterEnable = overlayManager.getCurrentConfig()
        Assert.assertEquals("Should be enabled", true, configAfterEnable?.enabled)

        overlayManager.setEnabled(false)
        val configAfterDisable = overlayManager.getCurrentConfig()
        Assert.assertEquals("Should be disabled", false, configAfterDisable?.enabled)
    }

    @Test
    fun testReduxStateSynchronization() {
        // Create a test route for Redux integration
        val testRoute = Route(
            id = "overlay_test_route",
            name = "Overlay Test Route",
            waypoints = listOf(
                Waypoint("wp1", 40.7128, -74.0060, Waypoint.Type.TURNPOINT, "Start", Instant.now(), "overlay_test_route"),
                Waypoint("wp2", 40.7589, -73.9851, Waypoint.Type.TURNPOINT, "Middle", Instant.now(), "overlay_test_route"),
                Waypoint("wp3", 40.7505, -73.9934, Waypoint.Type.TURNPOINT, "End", Instant.now(), "overlay_test_route")
            )
        )

        // Test Redux action integration
        val routeConfig = OverlayConfig(enabled = true, opacity = 0.8f)
        reduxStore.dispatch(MapAction.UpdateOverlayConfig(OverlayType.ROUTES, routeConfig))
        reduxStore.dispatch(MapAction.AddRoute(testRoute))

        // Allow time for state propagation in Android instrumentation context
        val latestState = reduxStore.state.value
        overlayManager.onReduxStateChanged(latestState)

        // Verify overlay manager processed the Redux state change
        val currentRoutes = overlayManager.getCurrentRoutes()
        Assert.assertFalse("Should have routes loaded", currentRoutes.isEmpty())
        Assert.assertEquals("Should have the test route", 1, currentRoutes.size)
        Assert.assertEquals("Route ID should match", "overlay_test_route", currentRoutes[0].id)

        // Test route updates through Redux
        val updatedRoute = testRoute.copy(name = "Updated Test Route")
        reduxStore.dispatch(MapAction.UpdateRoute(updatedRoute))

        val updatedState = reduxStore.state.value
        overlayManager.onReduxStateChanged(updatedState)

        // Verify overlay manager handled route updates
        val updatedRoutes = overlayManager.getCurrentRoutes()
        Assert.assertEquals("Route name should be updated", "Updated Test Route", updatedRoutes[0].name)
    }

    @Test
    fun testOverlayLifecycleWithRoutes() {
        // Test that overlay manager properly handles route additions/removals
        val routesBefore = overlayManager.getCurrentRoutes()
        Assert.assertTrue("Should start with no routes", routesBefore.isEmpty())

        // Add routes through Redux and verify overlay updates
        val route1 = Route("route1", "Test Route 1", listOf(
            Waypoint("wp1", 40.7128, -74.0060, Waypoint.Type.TURNPOINT, "WP1", Instant.now(), "route1")
        ))

        val route2 = Route("route2", "Test Route 2", listOf(
            Waypoint("wp2", 40.7589, -73.9851, Waypoint.Type.LAUNCH, "Launch", Instant.now(), "route2")
        ))

        reduxStore.dispatch(MapAction.UpdateOverlayConfig(OverlayType.ROUTES, OverlayConfig(enabled = true)))
        reduxStore.dispatch(MapAction.AddRoute(route1))
        reduxStore.dispatch(MapAction.AddRoute(route2))

        // Trigger state change
        overlayManager.onReduxStateChanged(reduxStore.state.value)

        var currentRoutes = overlayManager.getCurrentRoutes()
        Assert.assertEquals("Should have both routes", 2, currentRoutes.size)

        // Test route removal
        reduxStore.dispatch(MapAction.RemoveRoute("route1"))
        overlayManager.onReduxStateChanged(reduxStore.state.value)

        currentRoutes = overlayManager.getCurrentRoutes()
        Assert.assertEquals("Should have only route2 remaining", 1, currentRoutes.size)
        Assert.assertEquals("Remaining route should be route2", "route2", currentRoutes[0].id)

        // Test clear all routes
        reduxStore.dispatch(MapAction.ClearAllRoutes)
        overlayManager.onReduxStateChanged(reduxStore.state.value)

        currentRoutes = overlayManager.getCurrentRoutes()
        Assert.assertTrue("Should have no routes after clearing", currentRoutes.isEmpty())
    }

    @Test
    fun testOverlayConfigManagement() {
        // Test overlay configuration update and persistence
        val initialConfig = overlayManager.getCurrentConfig()
        Assert.assertNotNull("Should have initial config", initialConfig)

        // Test opacity configuration
        val transparentConfig = OverlayConfig(enabled = true, opacity = 0.5f)
        overlayManager.updateConfig(transparentConfig)

        val updatedConfig = overlayManager.getCurrentConfig()
        Assert.assertEquals("Opacity should be updated", 0.5f, updatedConfig?.opacity ?: 0.0f, 0.01f)
        Assert.assertTrue("Should be enabled", updatedConfig?.enabled ?: false)

        // Test disabling overlay
        val disabledConfig = OverlayConfig(enabled = false, opacity = 0.5f)
        overlayManager.updateConfig(disabledConfig)

        val disabledUpdated = overlayManager.getCurrentConfig()
        Assert.assertFalse("Should be disabled", disabledUpdated?.enabled ?: true)

        // Verify overlay is actually disabled (should clear routes)
        val route = Route("test_route", "Test Route", listOf(
            Waypoint("wp", 40.7128, -74.0060, Waypoint.Type.TURNPOINT, "WP", Instant.now(), "test_route")
        ))

        reduxStore.dispatch(MapAction.UpdateOverlayConfig(OverlayType.ROUTES, OverlayConfig(enabled = true)))
        reduxStore.dispatch(MapAction.AddRoute(route))
        overlayManager.onReduxStateChanged(reduxStore.state.value)

        // When disabled, routes should be cleared from overlay
        overlayManager.updateConfig(disabledConfig)
        val routesWhenDisabled = overlayManager.getCurrentRoutes()
        Assert.assertTrue("Routes should be cleared when overlay disabled", routesWhenDisabled.isEmpty())
    }

    @Test
    fun testRouteOverlayManagerSpecificFeatures() {
        // Test RouteOverlayManager specific methods and cache integration
        val routeManager = overlayManager as RouteOverlayManager

        // Test cache statistics availability (should not crash)
        val initialStats = routeManager.getRouteCacheStats()
        // Cache stats may be empty initially but should not throw exception
        Assert.assertNotNull("Cache stats should be available", initialStats)

        // Test GPS fix status handling (should not crash)
        routeManager.updateGPSFixStatus(true)
        routeManager.updateGPSFixStatus(false)

        // Test performance stats
        val perfStats = routeManager.getPerformanceStats()
        Assert.assertNotNull("Performance stats should be available", perfStats)
        Assert.assertTrue("Should contain overlay type info", perfStats.containsKey("overlayType"))
        Assert.assertEquals("Should be ROUTES overlay type", OverlayType.ROUTES, perfStats["overlayType"])

        // Test Redux store reference handling
        val testStore = MapStore()
        routeManager.setReduxStore(testStore)
        // Should not crash and should continue functioning
        val routesAfterStoreChange = routeManager.getCurrentRoutes()
        Assert.assertNotNull("Should handle store changes gracefully", routesAfterStoreChange)
    }

    @Test
    fun testWaypointSelectionIntegration() {
        // Test waypoint selection integration through overlay
        val route = Route("selection_test", "Selection Test Route", listOf(
            Waypoint("wp1", 40.7128, -74.0060, Waypoint.Type.TURNPOINT, "First", Instant.now(), "selection_test"),
            Waypoint("wp2", 40.7589, -73.9851, Waypoint.Type.TURNPOINT, "Second", Instant.now(), "selection_test")
        ))

        // Set up route and enable overlay
        reduxStore.dispatch(MapAction.UpdateOverlayConfig(OverlayType.ROUTES, OverlayConfig(enabled = true)))
        reduxStore.dispatch(MapAction.AddRoute(route))

        val stateWithRoute = reduxStore.state.value
        overlayManager.onReduxStateChanged(stateWithRoute)

        // Test waypoint selection through Redux
        val firstWaypoint = route.waypoints[0]
        reduxStore.dispatch(MapAction.SelectWaypoint(route.id, firstWaypoint.id))

        val stateWithSelection = reduxStore.state.value
        overlayManager.onReduxStateChanged(stateWithSelection)

        // Verify selection is tracked
        val currentSelection = stateWithSelection.selectedWaypoint
        Assert.assertNotNull("Should have waypoint selection", currentSelection)
        Assert.assertEquals("Should have correct route ID", route.id, currentSelection?.routeId)
        Assert.assertEquals("Should have correct waypoint ID", firstWaypoint.id, currentSelection?.waypointId)

        // Test deselection
        reduxStore.dispatch(MapAction.DeselectWaypoint)
        val stateAfterDeselect = reduxStore.state.value
        overlayManager.onReduxStateChanged(stateAfterDeselect)

        val noSelection = stateAfterDeselect.selectedWaypoint
        Assert.assertNull("Should have no selection after deselect", noSelection)
    }

    @Test
    fun testOverlayManagerErrorHandling() {
        // Test that overlay manager handles empty states gracefully

        // Test with empty routes list (should not crash)
        val emptyState = reduxStore.state.value.copy(routes = emptyList())
        overlayManager.onReduxStateChanged(emptyState)
        val routesAfterEmpty = overlayManager.getCurrentRoutes()
        Assert.assertTrue("Should handle empty routes list", routesAfterEmpty.isEmpty())

        // Test config with null values (should use defaults)
        val configWithNulls = overlayManager.getCurrentConfig()
        // Config should always be available with defaults
        Assert.assertNotNull("Config should always be available", configWithNulls)
    }
}
