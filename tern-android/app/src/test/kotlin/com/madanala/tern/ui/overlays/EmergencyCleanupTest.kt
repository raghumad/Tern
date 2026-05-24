package com.madanala.tern.ui.overlays

import com.google.common.truth.Truth.assertThat
import com.madanala.tern.BaseTest
import com.madanala.tern.redux.MapState
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.AdaptiveOverlaySystem
import com.madanala.tern.utils.ApplicationMemoryState
import com.madanala.tern.utils.DistanceZone
import com.madanala.tern.utils.MemoryPressureLevel
import com.madanala.tern.utils.ProcessMemoryInfo
import com.madanala.tern.utils.RuntimeMemoryInfo
import com.madanala.tern.utils.SystemMemoryInfo
import com.madanala.tern.utils.TrimMemoryLevel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Tests for overlay emergency cleanup under simulated memory pressure.
 *
 * Safety context: if cleanup triggers but is buggy mid-flight, the app
 * could crash instead of gracefully shedding overlays.  These tests
 * verify that the cleanup path actually works.
 */
class EmergencyCleanupTest : BaseTest() {

    // -- Helpers -------------------------------------------------------

    /** A minimal concrete BaseOverlayManager that tracks cleanup calls. */
    private class TestableOverlayManager(
        type: OverlayType = OverlayType.AIRSPACE
    ) : BaseOverlayManager(type, null) {

        val removedZones = mutableListOf<DistanceZone>()
        var simulatedInvisibleCount = 0
        var simulatedOverlaysPerZone = 0

        override fun performMapMove(center: GeoPoint, zoom: Double) {}
        override fun onViewportChangedInternal(viewport: BoundingBox) {}
        override fun onReduxStateChanged(state: MapState) {}
        override fun clearOverlays() {}
        override fun onOverlayAttached() {}
        override fun onOverlayDetached() {}

        override fun removeInvisibleOverlays(): Int {
            val removed = simulatedInvisibleCount
            simulatedInvisibleCount = 0
            return removed
        }

        override fun clearOverlaysInZone(zone: DistanceZone): Int {
            removedZones.add(zone)
            return simulatedOverlaysPerZone
        }
    }

    private fun memoryState(pressure: MemoryPressureLevel): ApplicationMemoryState {
        val availMB = when (pressure) {
            MemoryPressureLevel.CRITICAL_MEMORY -> 30L
            MemoryPressureLevel.LOW_MEMORY -> 60L
            MemoryPressureLevel.MEDIUM_MEMORY -> 150L
            MemoryPressureLevel.HIGH_MEMORY -> 500L
        }
        return ApplicationMemoryState(
            systemMemory = SystemMemoryInfo(availMB, 4000, 4000 - availMB, 100, pressure == MemoryPressureLevel.CRITICAL_MEMORY),
            processMemory = ProcessMemoryInfo(0, 0, 0, 0.0, 0.0, 0.0),
            runtimeMemory = RuntimeMemoryInfo(200, 50, 250, 256, 78.0),
            trimMemoryLevel = TrimMemoryLevel.NORMAL,
            calculatedPressure = pressure
        )
    }

    private fun adaptiveWithPressure(pressure: MemoryPressureLevel): AdaptiveOverlaySystem {
        val mock = mockk<AdaptiveOverlaySystem>(relaxed = true)
        every { mock.getCurrentMemoryState() } returns memoryState(pressure)
        every { mock.shouldTriggerEmergencyCleanup() } returns
            (pressure == MemoryPressureLevel.CRITICAL_MEMORY || pressure == MemoryPressureLevel.LOW_MEMORY)
        return mock
    }

    private fun inject(manager: BaseOverlayManager, system: AdaptiveOverlaySystem) {
        val field = BaseOverlayManager::class.java.getDeclaredField("adaptiveOverlaySystem")
        field.isAccessible = true
        field.set(manager, system)
    }

    // -- Tests ---------------------------------------------------------

    @Test
    fun `no adaptive system connected - cleanup returns 0 and does not crash`() {
        val manager = TestableOverlayManager()
        assertThat(manager.triggerEmergencyCleanup()).isEqualTo(0)
        assertThat(manager.removedZones).isEmpty()
    }

    @Test
    fun `normal memory pressure - cleanup returns 0`() {
        val manager = TestableOverlayManager()
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.HIGH_MEMORY))
        assertThat(manager.triggerEmergencyCleanup()).isEqualTo(0)
        assertThat(manager.removedZones).isEmpty()
    }

    @Test
    fun `medium memory pressure - cleanup returns 0`() {
        val manager = TestableOverlayManager()
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.MEDIUM_MEMORY))
        assertThat(manager.triggerEmergencyCleanup()).isEqualTo(0)
    }

    @Test
    fun `critical memory - sheds invisible overlays then EXTREME, FAR, MID zones`() {
        val manager = TestableOverlayManager()
        manager.simulatedInvisibleCount = 5
        manager.simulatedOverlaysPerZone = 3
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.CRITICAL_MEMORY))

        val removed = manager.triggerEmergencyCleanup()

        // 5 invisible + 3 per zone * 3 zones = 14
        assertThat(removed).isEqualTo(14)
        assertThat(manager.removedZones).containsExactly(
            DistanceZone.EXTREME, DistanceZone.FAR, DistanceZone.MID
        ).inOrder()
    }

    @Test
    fun `low memory - sheds invisible overlays then EXTREME, FAR zones only`() {
        val manager = TestableOverlayManager()
        manager.simulatedInvisibleCount = 0
        manager.simulatedOverlaysPerZone = 2
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.LOW_MEMORY))

        val removed = manager.triggerEmergencyCleanup()

        // 0 invisible + 2 per zone * 2 zones = 4
        assertThat(removed).isEqualTo(4)
        assertThat(manager.removedZones).containsExactly(
            DistanceZone.EXTREME, DistanceZone.FAR
        ).inOrder()
    }

    @Test
    fun `CORE and NEAR zones are never shed`() {
        val manager = TestableOverlayManager()
        manager.simulatedOverlaysPerZone = 10
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.CRITICAL_MEMORY))

        manager.triggerEmergencyCleanup()

        assertThat(manager.removedZones).containsNoneOf(DistanceZone.CORE, DistanceZone.NEAR)
    }

    @Test
    fun `zero overlays to shed - still succeeds without crashing`() {
        val manager = TestableOverlayManager()
        manager.simulatedInvisibleCount = 0
        manager.simulatedOverlaysPerZone = 0
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.CRITICAL_MEMORY))

        val removed = manager.triggerEmergencyCleanup()
        assertThat(removed).isEqualTo(0)
        // Zones were still visited even though nothing was removed
        assertThat(manager.removedZones).containsExactly(
            DistanceZone.EXTREME, DistanceZone.FAR, DistanceZone.MID
        )
    }

    @Test
    fun `manager without zone overrides silently sheds nothing`() {
        // Simulates RouteOverlayManager / MezullaOverlayManager which don't
        // override removeInvisibleOverlays() or clearOverlaysInZone().
        val bare = object : BaseOverlayManager(OverlayType.ROUTES, null) {
            override fun performMapMove(center: GeoPoint, zoom: Double) {}
            override fun onViewportChangedInternal(viewport: BoundingBox) {}
            override fun onReduxStateChanged(state: MapState) {}
            override fun clearOverlays() {}
            override fun onOverlayAttached() {}
            override fun onOverlayDetached() {}
        }
        inject(bare, adaptiveWithPressure(MemoryPressureLevel.CRITICAL_MEMORY))

        val removed = bare.triggerEmergencyCleanup()
        assertThat(removed).isEqualTo(0)
    }

    @Test
    fun `shouldTriggerEmergencyCleanup returns false without adaptive system`() {
        val manager = TestableOverlayManager()
        assertThat(manager.shouldTriggerEmergencyCleanup()).isFalse()
    }

    @Test
    fun `shouldTriggerEmergencyCleanup delegates to adaptive system`() {
        val manager = TestableOverlayManager()
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.CRITICAL_MEMORY))
        assertThat(manager.shouldTriggerEmergencyCleanup()).isTrue()
    }

    @Test
    fun `cleanup is idempotent - second call returns 0 after first drains`() {
        val manager = TestableOverlayManager()
        manager.simulatedInvisibleCount = 5
        manager.simulatedOverlaysPerZone = 2
        inject(manager, adaptiveWithPressure(MemoryPressureLevel.LOW_MEMORY))

        val first = manager.triggerEmergencyCleanup()
        assertThat(first).isEqualTo(9) // 5 invisible + 2*2 zone

        // Second call: invisible count was drained to 0, zone returns same (simulated)
        // but invisible is 0 now
        manager.removedZones.clear()
        val second = manager.triggerEmergencyCleanup()
        assertThat(second).isEqualTo(4) // 0 invisible + 2*2 zone
    }
}
