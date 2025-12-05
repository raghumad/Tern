package com.madanala.tern.utils

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import io.mockk.*
import kotlinx.coroutines.runBlocking
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
        coEvery { airspaceCache.downloadAndCache(countryCode) } returns true
        coEvery { pgSpotCache.downloadAndCache(countryCode) } returns listOf<OverlayFeature>()

        // When
        manager.preloadCountry(countryCode)

        // Then
        coVerify(exactly = 1) { airspaceCache.downloadAndCache(countryCode) }
        coVerify(exactly = 1) { pgSpotCache.downloadAndCache(countryCode) }
    }

    @Test
    fun `queryMultiCountryArea queries caches and aggregates results`() = runBlocking {
        // Given
        val center = GeoPoint(37.7749, -122.4194)
        val radiusKm = 50.0
        val countryCode = "US"
        
        // Simulate cached country
        val cachedCountriesField = UniversalCountryCacheManager::class.java.getDeclaredField("cachedCountries")
        cachedCountriesField.isAccessible = true
        (cachedCountriesField.get(manager) as MutableSet<String>).add(countryCode)

        // Mock cache responses
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

        every { airspaceCache.isCached(countryCode) } returns true
        every { airspaceCache.queryNearbyFeatures(countryCode, center, radiusKm) } returns listOf(airspaceFeature)
        
        every { pgSpotCache.isCached(countryCode) } returns true
        every { pgSpotCache.queryNearbyPGSpots(countryCode, center, any()) } returns listOf(pgSpotFeature)
        
        // When
        val results = manager.queryMultiCountryArea(center, radiusKm)

        // Then
        assertThat(results).hasSize(2)
        assertThat(results).contains(airspaceFeature)
        assertThat(results).contains(pgSpotFeature)
        verify { airspaceCache.queryNearbyFeatures(countryCode, center, radiusKm) }
        verify { pgSpotCache.queryNearbyPGSpots(countryCode, center, any()) }
    }

    @Test
    fun `performance stats for nearby queries`() = runBlocking {
        // Given
        val center = GeoPoint(46.8182, 8.2275) // Switzerland
        val radiusKm = 100.0
        val countryCode = "CH"
        
        // Simulate cached country
        val cachedCountriesField = UniversalCountryCacheManager::class.java.getDeclaredField("cachedCountries")
        cachedCountriesField.isAccessible = true
        (cachedCountriesField.get(manager) as MutableSet<String>).add(countryCode)

        // Mock cache with a slight delay to simulate work
        every { airspaceCache.isCached(countryCode) } returns true
        every { airspaceCache.queryNearbyFeatures(countryCode, center, radiusKm) } answers {
            Thread.sleep(10) // Simulate 10ms query time
            listOf(OverlayFeature(
                id = null,
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
    fun `validate cache after download`() = runBlocking {
        // Given
        val countryCode = "FR"
        
        // Mock successful download
        coEvery { airspaceCache.downloadAndCache(countryCode) } returns true
        coEvery { pgSpotCache.downloadAndCache(countryCode) } returns listOf<OverlayFeature>()
        
        // When
        manager.preloadCountry(countryCode)
        
        // Then
        // Verify that the manager considers it cached
        val cachedCountriesField = UniversalCountryCacheManager::class.java.getDeclaredField("cachedCountries")
        cachedCountriesField.isAccessible = true
        val cachedCountries = cachedCountriesField.get(manager) as Set<String>
        
        assertThat(cachedCountries).contains(countryCode)
    }
}
