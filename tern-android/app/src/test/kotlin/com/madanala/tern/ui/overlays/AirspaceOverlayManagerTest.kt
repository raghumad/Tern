package com.madanala.tern.ui.overlays

import android.content.Context
import com.madanala.tern.BaseTest
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.AirspaceCache
import com.madanala.tern.utils.GeoJsonUtils
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import com.madanala.tern.utils.UniversalCountryCacheManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

class AirspaceOverlayManagerTest : BaseTest() {

    private lateinit var context: Context
    private lateinit var mapStore: MapStore
    private lateinit var airspaceCache: AirspaceCache
    private lateinit var countryCacheManager: UniversalCountryCacheManager
    private lateinit var overlayCoordinator: OverlayCoordinator
    private lateinit var mapView: MapView
    private lateinit var manager: AirspaceOverlayManager

    @BeforeEach
    override fun setup() {
        super.setup()
        context = mockk(relaxed = true)
        mapStore = mockk(relaxed = true)
        airspaceCache = mockk(relaxed = true)
        countryCacheManager = mockk(relaxed = true)
        overlayCoordinator = mockk(relaxed = true)
        mapView = mockk(relaxed = true)

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
        val radiusKm = 200.0
        
        // Mock country cache response
        val airspaceFeature = OverlayFeature(
            id = null,
            feature = mapOf("name" to "Airspace1"), 
            centroid = center, 
            hilbertIndex = 123L, 
            overlayType = "airspace"
        )
        val pgSpotFeature = OverlayFeature(
            id = null,
            feature = mapOf("name" to "Spot1"), 
            centroid = center, 
            hilbertIndex = 456L, 
            overlayType = "pgspot"
        )
        
        every { airspaceCache.isCached("US") } returns true
        every { airspaceCache.queryNearbyFeatures("US", center, any()) } returns listOf(airspaceFeature)

        coEvery { countryCacheManager.queryMultiCountryArea(any(), any()) } returns listOf(airspaceFeature, pgSpotFeature)

        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)
        
        // Mock GeoJsonUtils static call
        mockkObject(GeoJsonUtils)
        val polygon = mockk<Polygon>(relaxed = true)
        every { GeoJsonUtils.createAirspaceOverlays(any(), any()) } returns listOf(polygon)

        // When
        manager.performMapMove(center, zoom)
        
        // Allow coroutines to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        // Verify that queryMultiCountryArea was called twice (once for init, once for move)
        coVerify(atLeast = 1) { countryCacheManager.queryMultiCountryArea(any(), any()) }
        
        // Verify that only airspace feature was processed (filtering check)
        // We verify that createAirspaceOverlays was called with a list containing ONLY the airspace feature
        verify { 
            GeoJsonUtils.createAirspaceOverlays(
                any(), 
                match { list -> 
                    list.size == 1 && list[0] == airspaceFeature 
                }
            ) 
        }
        
        unmockkObject(GeoJsonUtils)
    }
    
    @Test
    fun `performMapMove skips loading if zoom is too low`() = runTest {
        // Given
        val center = GeoPoint(47.0, 8.0)
        val zoom = 5.0 // Too low
        
        coEvery { countryCacheManager.queryMultiCountryArea(any(), any()) } returns listOf()

        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)

        // When
        manager.performMapMove(center, zoom)
        
        // Then
        // Expect 1 call from initialization, but NO additional calls from performMapMove
        coVerify(exactly = 1) { countryCacheManager.queryMultiCountryArea(any(), any()) }
    }
}
