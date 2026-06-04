package com.ternparagliding.utils
import com.ternparagliding.utils.cache.AirspaceCache
import com.ternparagliding.utils.cache.GeoJsonUtils

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AirspaceCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var airspaceCache: AirspaceCache

    @Before
    fun setUp() {
        context = mockk<Context>()
        cacheDir = tempFolder.newFolder("airspace_cache")
        
        // Mock context.cacheDir to return our temp folder's parent
        every { context.cacheDir } returns tempFolder.root

        airspaceCache = AirspaceCache(context)
        
        mockkObject(GeoJsonUtils)
        every { GeoJsonUtils.isNdGeoJson(any()) } returns true
    }

    @After
    fun tearDown() {
        airspaceCache.clearCache()
        unmockkObject(GeoJsonUtils)
    }

    @Test
    fun `isCached returns false when no data exists`() {
        assertFalse(airspaceCache.isCached("US"))
    }

    @Test
    fun `downloadAndCache downloads, parses and caches data`() = runBlocking {
        val countryCode = "CH"
        val featureMap = mapOf(
            "type" to "Feature",
            "geometry" to mapOf("type" to "Point", "coordinates" to listOf(8.5, 47.3)),
            "properties" to mapOf("name" to "Test Airspace")
        )

        // Mock download streaming
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            val processFeature = secondArg<(Map<String, Any>) -> Unit>()
            processFeature.invoke(featureMap)
            true
        }

        val result = airspaceCache.downloadAndCache(countryCode)

        assertTrue(result)
        
        // Verify files exist
        val flexFile = File(cacheDir, "${countryCode}_airspace.flex")
        val idxFile = File(cacheDir, "${countryCode}_airspace.idx")
        
        assertTrue(flexFile.exists())
        assertTrue(idxFile.exists())
        
        // Verify isCached returns true
        assertTrue(airspaceCache.isCached(countryCode))
    }

    @Test
    fun `downloadAndCache handles invalid GeoJSON`() = runBlocking {
        val countryCode = "IT"

        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } returns false

        val result = airspaceCache.downloadAndCache(countryCode)

        assertFalse(result)
        assertFalse(airspaceCache.isCached(countryCode))
    }

    @Test
    fun `getCachedAirspaces returns cached data`() = runBlocking {
        val countryCode = "DE"
        val featureMap = mapOf(
            "type" to "Feature", 
            "properties" to mapOf("name" to "Test Airspace"),
            "geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(listOf(listOf(10.0, 50.0), listOf(10.0, 50.1), listOf(10.1, 50.1), listOf(10.1, 50.0), listOf(10.0, 50.0))))
        )

        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            val processFeature = secondArg<(Map<String, Any>) -> Unit>()
            processFeature.invoke(featureMap)
            true
        }
        
        airspaceCache.downloadAndCache(countryCode)

        // Verify we can retrieve it
        val features = airspaceCache.getCachedAirspaces(countryCode)
        assertNotNull(features)
        assertTrue(features!!.isNotEmpty())
        assertEquals("airspace", features[0].overlayType)
    }
}
