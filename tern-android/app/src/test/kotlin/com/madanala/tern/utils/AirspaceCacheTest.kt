package com.madanala.tern.utils

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.After
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
        // AirspaceCache appends "airspace_cache" to the context.cacheDir
        every { context.cacheDir } returns tempFolder.root

        airspaceCache = AirspaceCache(context)
    }

    @After
    fun tearDown() {
        airspaceCache.clearCache()
    }

    @Test
    fun `isCached returns false when no data exists`() {
        assertThat(airspaceCache.isCached("US")).isFalse()
    }

    @Test
    fun `cacheData creates cache files and updates index`() {
        val countryCode = "CH"
        // Minimal valid NDGeoJSON
        val ndGeoJson = """
            {"type":"Feature","geometry":{"type":"Point","coordinates":[8.5,47.3]},"properties":{"name":"Test"}}
        """.trimIndent()

        airspaceCache.cacheData(countryCode, ndGeoJson)

        // Verify files exist
        val flexFile = File(cacheDir, "${countryCode}_airspace.flex")
        val idxFile = File(cacheDir, "${countryCode}_airspace.idx")
        
        assertThat(flexFile.exists()).isTrue()
        assertThat(idxFile.exists()).isTrue()
        
        // Verify isCached returns true
        assertThat(airspaceCache.isCached(countryCode)).isTrue()
    }

    @Test
    fun `validateCacheIntegrity returns false for missing files`() {
        // Manually create index but not flex file
        val countryCode = "FR"
        val idxFile = File(cacheDir, "${countryCode}_airspace.idx")
        idxFile.createNewFile()
        idxFile.writeText("{}") // Minimal content

        // Should fail because flex file is missing
        // We need to access the private method or rely on isCached calling it
        // isCached calls validateCacheIntegrity if timestamp exists
        
        // We can't easily inject timestamp without reflection or exposing methods.
        // However, we can try to cache data, then delete a file, then check isCached.
        
        val ndGeoJson = """
            {"type":"Feature","geometry":{"type":"Point","coordinates":[2.3,48.8]},"properties":{"name":"Paris"}}
        """.trimIndent()
        airspaceCache.cacheData(countryCode, ndGeoJson)
        
        // Verify it's cached
        assertThat(airspaceCache.isCached(countryCode)).isTrue()
        
        // Corrupt it by deleting flex file
        val flexFile = File(cacheDir, "${countryCode}_airspace.flex")
        flexFile.delete()
        
        // Should now be false
        assertThat(airspaceCache.isCached(countryCode)).isFalse()
    }

    @Test
    fun `cacheData rejects invalid NDGeoJSON`() {
        val countryCode = "IT"
        val invalidJson = "Not JSON"

        airspaceCache.cacheData(countryCode, invalidJson)

        assertThat(airspaceCache.isCached(countryCode)).isFalse()
    }
}
