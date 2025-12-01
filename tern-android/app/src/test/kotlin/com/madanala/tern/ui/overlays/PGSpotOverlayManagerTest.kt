package com.madanala.tern.ui.overlays

import android.content.Context
import com.madanala.tern.BaseTest
import com.madanala.tern.redux.MapStore
import com.madanala.tern.utils.PGSpotCache
import com.madanala.tern.utils.WeatherCache
import com.madanala.tern.utils.WeatherAPI
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import com.madanala.tern.utils.UniversalCountryCacheManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class PGSpotOverlayManagerTest : BaseTest() {

    private lateinit var context: Context
    private lateinit var mapStore: MapStore
    private lateinit var pgSpotCache: PGSpotCache
    private lateinit var weatherAPI: WeatherAPI
    private lateinit var weatherCache: WeatherCache
    private lateinit var countryCacheManager: UniversalCountryCacheManager
    private lateinit var overlayCoordinator: OverlayCoordinator
    private lateinit var mapView: MapView
    private lateinit var manager: PGSpotOverlayManager

    @BeforeEach
    override fun setup() {
        super.setup()
        context = mockk(relaxed = true)
        mapStore = mockk(relaxed = true)
        pgSpotCache = mockk(relaxed = true)
        weatherAPI = mockk(relaxed = true)
        weatherCache = mockk(relaxed = true)
        countryCacheManager = mockk(relaxed = true)
        overlayCoordinator = mockk(relaxed = true)
        mapView = mockk(relaxed = true)

        // Mock MapStore state
        val mockState = mockk<com.madanala.tern.redux.MapState>(relaxed = true)
        val mockOverlayState = mockk<com.madanala.tern.redux.OverlayState>(relaxed = true)
        val mockConfig = com.madanala.tern.redux.OverlayConfig(enabled = true)
        
        every { mockState.overlayState } returns mockOverlayState
        every { mockOverlayState.pgSpots } returns mockConfig
        
        val mockStateFlow = kotlinx.coroutines.flow.MutableStateFlow(mockState)
        every { mapStore.state } returns mockStateFlow

        // Mock static CountryUtils
        mockkObject(com.madanala.tern.utils.CountryUtils)
        every { com.madanala.tern.utils.CountryUtils.getCountryCodeFromGeoPoint(any(), any()) } returns "US"

        manager = PGSpotOverlayManager(context, mapStore, pgSpotCache, weatherAPI, weatherCache)
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
        
        // Mock country cache response
        val pgSpotFeature = OverlayFeature(mapOf("name" to "Spot1"), center, 456L, "pgspot")
        
        every { pgSpotCache.isCached("US") } returns true
        every { pgSpotCache.queryNearbyPGSpots("US", center, any()) } returns listOf(pgSpotFeature)

        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)

        // When
        manager.performMapMove(center, zoom)
        
        // Allow coroutines to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        // Verify that pgSpotCache was queried
        verify { pgSpotCache.queryNearbyPGSpots("US", center, any()) }
    }
    
    @Test
    fun `performMapMove skips loading if zoom is too low`() = runTest {
        // Given
        val center = GeoPoint(47.0, 8.0)
        val zoom = 5.0 // Too low
        
        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)

        // When
        manager.performMapMove(center, zoom)
        
        // Then
        coVerify(exactly = 0) { countryCacheManager.queryMultiCountryArea(any(), any()) }
    }

    @Test
    fun `performMapMove ignores 0,0 coordinates`() = runTest {
        // Given
        val center = GeoPoint(0.0, 0.0)
        val zoom = 12.0
        
        manager.setOverlayCoordinator(overlayCoordinator)
        manager.initialize(mapView)

        // When
        manager.performMapMove(center, zoom)
        
        // Then
        coVerify(exactly = 0) { countryCacheManager.queryMultiCountryArea(any(), any()) }
        verify(exactly = 0) { pgSpotCache.queryNearbyPGSpots(any(), any(), any()) }
    }
}
