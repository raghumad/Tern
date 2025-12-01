package com.madanala.tern.utils

import android.content.Context
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PGSpotCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var pgSpotCache: PGSpotCache

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0

        context = mockk<Context>()
        // SpatialDiskCache creates "${cacheName}_cache". PGSpotCache uses "pgspots".
        // So we expect "pgspots_cache".
        // We don't need to create it manually if we mock context.cacheDir correctly,
        // but creating it ensures we have a handle to it.
        // Actually, SpatialDiskCache calls mkdirs(), so we just need to point to it.
        
        every { context.cacheDir } returns tempFolder.root
        cacheDir = File(tempFolder.root, "pgspots_cache")

        pgSpotCache = PGSpotCache(context)
        mockkObject(GeoJsonUtils)
    }

    @After
    fun tearDown() {
        pgSpotCache.clearCache()
        unmockkObject(GeoJsonUtils)
        unmockkStatic(Log::class)
    }

    @Test
    fun `isCached returns false when no data exists`() {
        assertFalse(pgSpotCache.isCached("US"))
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
                  "properties": {"name":"Test Spot", "id": 123}
                }
              ]
            }
        """.trimIndent()

        // Mock download
        coEvery { GeoJsonUtils.downloadGeoJson(any()) } returns geoJson

        val result = pgSpotCache.downloadAndCache(countryCode)

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        
        // Verify files exist
        val flexFile = File(cacheDir, "${countryCode}_pgspots.flex")
        val idxFile = File(cacheDir, "${countryCode}_pgspots.idx")
        
        assertTrue("Flex file should exist at ${flexFile.absolutePath}", flexFile.exists())
        assertTrue("Index file should exist at ${idxFile.absolutePath}", idxFile.exists())
        
        // Verify isCached returns true
        assertTrue("isCached should return true", pgSpotCache.isCached(countryCode))
    }

    @Test
    fun `downloadAndCache handles invalid GeoJSON`() = runBlocking {
        val countryCode = "IT"
        val invalidJson = "Not JSON"

        coEvery { GeoJsonUtils.downloadGeoJson(any()) } returns invalidJson

        val result = pgSpotCache.downloadAndCache(countryCode)

        assertNull(result)
        assertFalse(pgSpotCache.isCached(countryCode))
    }
}
