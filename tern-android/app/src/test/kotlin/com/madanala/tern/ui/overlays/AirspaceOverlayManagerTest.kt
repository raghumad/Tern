package com.madanala.tern.ui.overlays

import android.content.Context
import com.madanala.tern.BaseTest
import com.madanala.tern.redux.MapStore
import com.madanala.tern.redux.OverlayType
import com.madanala.tern.utils.AirspaceCache
import com.madanala.tern.utils.GeoJsonUtils
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import com.madanala.tern.utils.UniversalCountryCacheManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Polygon

class AirspaceOverlayManagerTest : BaseTest() {

    private lateinit var context: Context
    private lateinit var mapStore: MapStore
    private lateinit var airspaceCache: AirspaceCache
    private lateinit var countryCacheManager: UniversalCountryCacheManager
    private lateinit var overlayCoordinator: OverlayCoordinator
    private lateinit var mapView: MapView
    private lateinit var manager: AirspaceOverlayManager
    private lateinit var airspaceLayer: FolderOverlay

    @BeforeEach
    override fun setup() {
        super.setup()
        context = mockk(relaxed = true)
        mapStore = mockk(relaxed = true)
        airspaceCache = mockk(relaxed = true)
        countryCacheManager = mockk(relaxed = true)
        overlayCoordinator = mockk(relaxed = true)
        mapView = mockk(relaxed = true)
        airspaceLayer = FolderOverlay()

        // Mock MapStore state
        val mockState = mockk<com.madanala.tern.redux.MapState>(relaxed = true)
        val mockOverlayState = mockk<com.madanala.tern.redux.OverlayState>(relaxed = true)
        val mockConfig = com.madanala.tern.redux.OverlayConfig(enabled = true)

        every { mockState.overlayState } returns mockOverlayState
        every { mockOverlayState.airspaces } returns mockConfig

        val mockStateFlow = kotlinx.coroutines.flow.MutableStateFlow(mockState)
        every { mapStore.state } returns mockStateFlow

        // Mock static CountryUtils
        mockkObject(com.madanala.tern.utils.CountryUtils)
        every { com.madanala.tern.utils.CountryUtils.getCountryCodeFromGeoPoint(any(), any()) } returns "US"

        // Wire the coordinator to return a real FolderOverlay for airspaces
        every { overlayCoordinator.getLayerForType(OverlayType.AIRSPACE) } returns airspaceLayer

        manager = AirspaceOverlayManager(context, mapStore, airspaceCache)
        manager.setCountryCacheManager(countryCacheManager)
        // Mock MapView center
        every { mapView.mapCenter } returns GeoPoint(0.0, 0.0)
        every { mapView.zoomLevelDouble } returns 12.0
        every { mapView.overlays } returns mutableListOf()
    }

    @Test
    fun `performMapMove triggers loading when conditions met`() = runTest {
        // Given
        val center = GeoPoint(47.0, 8.0)
        val zoom = 12.0
        val radiusKm = 100.0

        // Mock country cache response
        val airspaceFeature = OverlayFeature(
            internalId = null,
            feature = mapOf("name" to "Airspace1"),
            centroid = center,
            hilbertIndex = 123L,
            overlayType = "airspace"
        )
        val pgSpotFeature = OverlayFeature(
            internalId = null,
            feature = mapOf("name" to "Spot1"),
            centroid = center,
            hilbertIndex = 456L,
            overlayType = "pgspot"
        )

        every { airspaceCache.isCached("US") } returns true
        every { airspaceCache.queryNearbyFeatures("US", center, any()) } returns listOf(airspaceFeature)

        coEvery { countryCacheManager.queryMultiCountryArea(any(), any(), any()) } returns listOf(airspaceFeature, pgSpotFeature)

        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)

        // Mock GeoJsonUtils static call
        mockkObject(GeoJsonUtils)
        val polygon = mockk<Polygon>(relaxed = true)
        every { GeoJsonUtils.createAirspaceOverlaysIncrementally(any(), any(), any()) } returns listOf(polygon)

        // When
        manager.performMapMove(center, zoom)

        // Allow coroutines to run
        kotlinx.coroutines.delay(100)

        // Then
        verify(atLeast = 1) { countryCacheManager.onLocationChanged(center) }
        coVerify(atLeast = 1) { countryCacheManager.queryMultiCountryArea(center, 100.0, 300) }

        // Verify that only airspace feature was processed (filtering check)
        // We verify that createAirspaceOverlaysIncrementally was called with a list containing ONLY the airspace feature
        verify {
            GeoJsonUtils.createAirspaceOverlaysIncrementally(
                any(),
                match { list ->
                    list.size == 1 && list[0] == airspaceFeature
                },
                any()
            )
        }

        unmockkObject(GeoJsonUtils)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `airspace polygons are added to FolderOverlay not to map overlays directly`() = runTest {
        // Given: a real FolderOverlay and a spy on map.overlays to detect direct adds
        val mapOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()
        every { mapView.overlays } returns mapOverlays
        every { mapView.mapCenter } returns GeoPoint(47.0, 8.0)

        val center = GeoPoint(47.0, 8.0)
        val airspaceFeature = OverlayFeature(
            internalId = null,
            feature = mapOf(
                "name" to "TMA Zurich",
                "properties" to mapOf("name" to "TMA Zurich")
            ),
            centroid = center,
            hilbertIndex = 100L,
            overlayType = "airspace"
        )

        coEvery { countryCacheManager.queryMultiCountryArea(any(), any(), any()) } returns listOf(airspaceFeature)

        mockkObject(GeoJsonUtils)
        val polygon = Polygon()
        polygon.fillPaint.color = android.graphics.Color.argb(0x40, 0xFF, 0x00, 0x00)
        every { GeoJsonUtils.createAirspaceOverlaysIncrementally(any(), any(), any()) } returns listOf(polygon)

        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)

        // When
        manager.performMapMove(center, 12.0)

        // The rendering pipeline uses Dispatchers.IO for polygon creation and
        // Dispatchers.Main for adding to the layer. Give the IO work time to complete.
        testDispatcher.scheduler.advanceUntilIdle()
        Thread.sleep(300)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: polygon must be in the FolderOverlay, NOT in map.overlays directly
        assertTrue(
            airspaceLayer.items.contains(polygon),
            "Polygon must be in the coordinator's airspace FolderOverlay"
        )
        assertFalse(
            mapOverlays.any { it is Polygon },
            "No polygons should be added directly to map.overlays — they must go through the FolderOverlay"
        )

        unmockkObject(GeoJsonUtils)
    }

    @Test
    fun `clearOverlays removes polygons from FolderOverlay`() = runTest {
        // Given: polygon already in the airspace layer
        val polygon = Polygon()
        airspaceLayer.add(polygon)
        assertEquals(1, airspaceLayer.items.size)

        every { mapView.mapCenter } returns GeoPoint(47.0, 8.0)

        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)

        // Manually seed the tracking map so clearOverlays has something to clear
        val field = AirspaceOverlayManager::class.java.getDeclaredField("currentlyRenderedAirspaces")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val rendered = field.get(manager) as MutableMap<String, Polygon>
        rendered["test_airspace"] = polygon

        // When
        manager.clearOverlays()

        // Then
        assertTrue(airspaceLayer.items.isEmpty(), "FolderOverlay should be empty after clearOverlays")
        assertEquals(0, manager.getRenderedCount())
    }
}
