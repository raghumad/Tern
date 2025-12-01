package com.madanala.tern.utils

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
        // Minimal valid GeoJSON
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {"type":"Point","coordinates":[8.5,47.3]},
                  "properties": {"name":"Test Airspace"}
                }
              ]
            }
        """.trimIndent()

        // Mock download
        coEvery { GeoJsonUtils.downloadGeoJson(any()) } returns geoJson

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
        val invalidJson = "Not JSON"

        coEvery { GeoJsonUtils.downloadGeoJson(any()) } returns invalidJson

        val result = airspaceCache.downloadAndCache(countryCode)

        assertFalse(result)
        assertFalse(airspaceCache.isCached(countryCode))
    }

    @Test
    fun `getCachedAirspaces returns cached data`() = runBlocking {
        val countryCode = "DE"
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {"name": "Test Airspace"},
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [
                        [10.0, 50.0],
                        [10.0, 50.1],
                        [10.1, 50.1],
                        [10.1, 50.0],
                        [10.0, 50.0]
                      ]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        coEvery { GeoJsonUtils.downloadGeoJson(any()) } returns geoJson
        airspaceCache.downloadAndCache(countryCode)

        // Verify we can retrieve it
        val features = airspaceCache.getCachedAirspaces(countryCode)
        assertNotNull(features)
        assertTrue(features!!.isNotEmpty())
        assertEquals("airspace", features[0].overlayType)
    }
}
