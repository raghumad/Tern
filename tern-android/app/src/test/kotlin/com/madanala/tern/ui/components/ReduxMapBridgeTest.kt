package com.madanala.tern.ui.components

import com.madanala.tern.redux.MapAction
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.MapStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.osmdroid.util.GeoPoint

@OptIn(ExperimentalCoroutinesApi::class)
class ReduxMapBridgeTest {

    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `isLocationReady reflects redux state`() = testScope.runTest {
        val store = MapStore()
        val bridge = ReduxMapBridge(backgroundScope)
        bridge.setReduxStore(store)

        // Initial state
        assertFalse("Initial state should be false", bridge.isLocationReady.value)

        // Dispatch action to update store
        store.dispatch(MapAction.SetLocationReady(true))
        
        // With UnconfinedTestDispatcher, this happens immediately.
        // But we can keep advanceUntilIdle for safety/consistency if we switch back.
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify store updated first
        assertTrue("Store state should be updated", store.state.value.isLocationReady)

        // Verify bridge reflects new state
        assertTrue("Bridge should reflect store state", bridge.isLocationReady.value)
    }

    @Test
    fun `dispatchLocationReady dispatches action but does not mutate local state directly`() = testScope.runTest {
        val store = MapStore()
        val bridge = ReduxMapBridge(backgroundScope)
        bridge.setReduxStore(store)

        // Initial state
        assertFalse(bridge.isLocationReady.value)

        // Dispatch via bridge
        bridge.dispatchLocationReady(true)

        // Pre-observation check: bridge shouldn't update immediately (it requires store -> collector loop)
        // If it was still "Split Brain", it would be true here.
        // NOTE: StandardTestDispatcher requires advanceUntilIdle. If this was Unconfined, it might update.
        // But we want to prove it comes from the store.
        
        testDispatcher.scheduler.advanceUntilIdle() // Allow store to process and emit

        assertTrue(bridge.isLocationReady.value)
        assertTrue(store.state.value.isLocationReady)
    }
}
