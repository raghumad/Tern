package com.madanala.tern.redux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for overlay state management in Redux store
 */
class OverlayStateTest {

    @Test
    fun `overlay enabled state updates correctly`() {
        // Initial state
        val initialState = MapState()

        // Test enabling airspaces
        val action1 = MapAction.SetOverlayEnabled(OverlayType.AIRSPACE, enabled = true)
        val state1 = mapReducer(initialState, action1)

        assertEquals(true, state1.overlayState.airspaces.enabled)

        // Test enabling PG spots
        val action2 = MapAction.SetOverlayEnabled(OverlayType.PG_SPOTS, enabled = true)
        val state2 = mapReducer(state1, action2)

        assertEquals(true, state1.overlayState.airspaces.enabled) // Should remain true
        assertEquals(true, state2.overlayState.pgSpots.enabled)
    }

    @Test
    fun `overlay config updates correctly`() {
        val initialState = MapState()
        val newConfig = OverlayConfig(enabled = true, opacity = 0.5f, filterRadiusMiles = 200.0)

        val action = MapAction.UpdateOverlayConfig(OverlayType.PG_SPOTS, newConfig)
        val newState = mapReducer(initialState, action)

        assertEquals(newConfig, newState.overlayState.pgSpots)
    }

    @Test
    fun `default overlay state configuration`() {
        val state = MapState()

        // Airspaces should be enabled by default
        assertEquals(true, state.overlayState.airspaces.enabled)
        assertEquals(0.8f, state.overlayState.airspaces.opacity)

        // PG spots should be disabled by default
        assertEquals(false, state.overlayState.pgSpots.enabled)
        assertEquals(0.8f, state.overlayState.pgSpots.opacity)

        // All should have same default filter radius
        assertEquals(300.0, state.overlayState.airspaces.filterRadiusMiles, 0.0)
        assertEquals(300.0, state.overlayState.pgSpots.filterRadiusMiles, 0.0)
    }

    @Test
    fun `overlay state immutability`() {
        val state1 = MapState()

        // Modify airspaces config
        val action = MapAction.UpdateOverlayConfig(
            OverlayType.AIRSPACE,
            OverlayConfig(enabled = false, opacity = 0.2f)
        )
        val state2 = mapReducer(state1, action)

        // Original state should be unchanged
        assertEquals(true, state1.overlayState.airspaces.enabled)
        assertEquals(0.8f, state1.overlayState.airspaces.opacity, 0.0f)

        // New state should be updated
        assertEquals(false, state2.overlayState.airspaces.enabled)
        assertEquals(0.2f, state2.overlayState.airspaces.opacity, 0.0f)

        // Other overlays should be unchanged
        assertEquals(false, state1.overlayState.pgSpots.enabled)
        assertEquals(false, state2.overlayState.pgSpots.enabled)
    }
}
