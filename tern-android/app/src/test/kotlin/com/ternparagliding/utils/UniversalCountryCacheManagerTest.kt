package com.ternparagliding.utils
import com.ternparagliding.utils.cache.AirspaceCache
import com.ternparagliding.utils.cache.PGSpotCache
import com.ternparagliding.utils.cache.UniversalCountryCacheManager
import com.ternparagliding.utils.geo.CountryUtils

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint
import kotlin.system.measureTimeMillis

class UniversalCountryCacheManagerTest {

    private lateinit var context: Context
    private lateinit var airspaceCache: AirspaceCache
    private lateinit var pgSpotCache: PGSpotCache
    private lateinit var manager: UniversalCountryCacheManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        airspaceCache = mockk(relaxed = true)
        pgSpotCache = mockk(relaxed = true)

        // Mock Geocoder behavior (simplified since we can't easily mock Android Geocoder in unit tests without Robolectric)
        // Ideally we'd wrap Geocoder in a provider, but for now we'll rely on the fallback "US" or mock the private method via reflection if needed.
        // However, UniversalCountryCacheManager uses a private method getCurrentCountry.
        // We can trigger onLocationChanged and verify interactions.
        
        manager = UniversalCountryCacheManager(context, airspaceCache, pgSpotCache)
    }

    @Test
    fun `preloadCountry triggers downloads for both caches`() = runBlocking {
        // Given
        val countryCode = "US"
        coEvery { pgSpotCache.downloadAndCache(countryCode) } returns listOf()

        // When
        manager.preloadCountry(countryCode)

        // Then
        coVerify(exactly = 1) { airspaceCache.downloadAndCache(countryCode) }
        coVerify(exactly = 1) { pgSpotCache.downloadAndCache(countryCode) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `queryMultiCountryArea queries caches and aggregates results`() = runBlocking {
        // Given
        val center = GeoPoint(37.7749, -122.4194)
        val radiusKm = 50.0
        val countryCode = "US"
        
        // Simulate cached country by mocking the underlying caches
        com.ternparagliding.utils.geo.CountryUtils.setTestCountryCode(countryCode)

        // Mock cache responses
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

        val radiusMiles = radiusKm * 0.621371
        every { airspaceCache.isCached(countryCode) } returns true
        every { airspaceCache.queryNearbyFeatures(countryCode, center, radiusMiles) } returns listOf(airspaceFeature)
        
        every { pgSpotCache.isCached(countryCode) } returns true
        every { pgSpotCache.queryNearbyPGSpots(countryCode, center, any()) } returns listOf(pgSpotFeature)
        
        // When
        val results = manager.queryMultiCountryArea(center, radiusKm)

        // Then
        assertThat(results).hasSize(2)
        assertThat(results).contains(airspaceFeature)
        assertThat(results).contains(pgSpotFeature)
        verify { airspaceCache.queryNearbyFeatures(countryCode, center, radiusMiles) }
        verify { pgSpotCache.queryNearbyPGSpots(countryCode, center, any()) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `performance stats for nearby queries`() = runBlocking {
        // Given
        val center = GeoPoint(46.8182, 8.2275) // Switzerland
        val radiusKm = 100.0
        val countryCode = "CH"
        
        // Simulate cached country by mocking the underlying caches
        com.ternparagliding.utils.geo.CountryUtils.setTestCountryCode(countryCode)

        // Mock cache with a slight delay to simulate work
        val radiusMiles = radiusKm * 0.621371
        every { airspaceCache.isCached(countryCode) } returns true
        every { airspaceCache.queryNearbyFeatures(countryCode, center, radiusMiles) } answers {
            Thread.sleep(10) // Simulate 10ms query time
            listOf(OverlayFeature(
                internalId = null,
                feature = emptyMap(), 
                centroid = center, 
                hilbertIndex = 0L, 
                overlayType = "airspace"
            ))
        }

        // When
        val timeMs = measureTimeMillis {
            manager.queryMultiCountryArea(center, radiusKm)
        }

        // Then
        println("Query performance: ${timeMs}ms")
        assertThat(timeMs).isAtLeast(10)
        // Assert it's fast enough (e.g., under 100ms for a simple query)
        assertThat(timeMs).isLessThan(200) 
    }
    
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `validate cache after download`() = runBlocking {
        // Given
        val countryCode = "FR"
        
        // Mock successful download
        coEvery { airspaceCache.downloadAndCache(countryCode) } returns true
        coEvery { pgSpotCache.downloadAndCache(countryCode) } returns listOf()
        
        // When
        manager.preloadCountry(countryCode)
        
        // Then
        // Simulate that caches are now present on disk for the following check
        every { airspaceCache.isCached(countryCode) } returns true
        
        // Verify that the manager considers it cached via the public API
        assertThat(manager.isCountryCached(countryCode)).isTrue()
    }
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `preloadCountry adds country to cache immediately before download completes`() = runBlocking {
        // Given
        val countryCode = "US"
        
        // Mock a slow download
        coEvery { airspaceCache.downloadAndCache(countryCode) } coAnswers {
            kotlinx.coroutines.delay(1000) // Simulate delay
            true
        }
        coEvery { pgSpotCache.downloadAndCache(countryCode) } returns listOf()

        // When (launch in parallel so we can check state during execution)
        val job = this.launch {
            manager.preloadCountry(countryCode)
        }
        
        // Allow coroutine to start and reach the delay
        kotlinx.coroutines.delay(100) 
        
        // Then (verify country is marked as downloading)
        assertThat(manager.isCountryDownloading(countryCode)).isTrue()
        
        job.cancel()
    }
}
