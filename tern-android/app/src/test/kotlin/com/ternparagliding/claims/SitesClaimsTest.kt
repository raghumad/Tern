package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.overlay.pgspot.overlayFeaturesToGeoJson
import com.ternparagliding.utils.cache.GeoJsonUtils
import com.ternparagliding.utils.cache.PGSpotCache
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.osmdroid.util.GeoPoint

/**
 * Claim **K3 · Sites (PG spots)** — the promises about where a pilot can launch
 * and land, demonstrated by driving the real PG-spot cache + query stack (no
 * screenshots, no emulator). See [docs/claims.md].
 *
 * PG spots share the `SpatialDiskCache` infrastructure with airspace, so Offline
 * and Resilient exercise the same proven machinery with site data; the
 * site-specific risk is **name resolution** (nested `properties.name`).
 */
class SitesClaimsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cache: PGSpotCache

    @Before
    fun setUp() {
        context = mockk<Context>()
        every { context.cacheDir } returns tempFolder.root
        cache = PGSpotCache(context)
        mockkObject(GeoJsonUtils)
        every { GeoJsonUtils.isNdGeoJson(any()) } returns true
    }

    @After
    fun tearDown() {
        cache.clearCache()
        unmockkObject(GeoJsonUtils)
    }

    /** One PG-spot Point feature with a nested name (the PG-Earth / OpenAIP shape). */
    private fun site(lat: Double, lon: Double, name: String): String =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},""" +
            """"properties":{"name":"$name","id":${name.hashCode()}}}"""

    /** Pre-flight prefetch: PGSpotCache pulls via the `downloadGeoJson` string seam. */
    private fun prefetch(region: String, vararg sites: String) = runBlocking {
        coEvery { GeoJsonUtils.downloadGeoJson(any(), any()) } returns sites.joinToString("\n")
        assertNotNull("$region: prefetch should cache sites", cache.downloadAndCache(region))
    }

    /**
     * **CLAIM K3 · Offline.** Prefetch the region's sites once at home, then with
     * the network CUT every point along the flight still has its nearby sites —
     * served from cache, with **zero network calls**.
     */
    @Test
    fun `offline - PG sites along the corridor are served from cache with zero network calls`() {
        val corridor = (0 until 6).map { i -> (32.20 + i * 0.05) to 76.70 }
        prefetch("IN", *corridor.mapIndexed { i, (la, lo) -> site(la, lo, "SITE-$i") }.toTypedArray())

        var calls = 0
        coEvery { GeoJsonUtils.downloadGeoJson(any(), any()) } answers {
            calls++
            throw IllegalStateException("network used in flight — sites offline claim broken")
        }

        corridor.forEach { (la, lo) ->
            assertTrue(
                "no site served offline at ($la, $lo)",
                cache.queryAllCachedNearby(GeoPoint(la, lo), 50.0).isNotEmpty(),
            )
        }
        assertEquals("the offline flight made network calls", 0, calls)
    }

    /**
     * **CLAIM K3 · Frictionless.** Sites surface by *location*, not a search box:
     * the pilot's nearby sites appear, while a distant region's sites do not.
     */
    @Test
    fun `frictionless - only sites near the pilot surface, distant regions do not`() {
        prefetch("IN", *(0 until 5).map { site(32.20 + it * 0.02, 76.70, "BIR-$it") }.toTypedArray())
        prefetch("FR", *(0 until 5).map { site(45.90 + it * 0.02, 6.10, "ANNECY-$it") }.toTypedArray())

        val near = cache.queryAllCachedNearby(GeoPoint(32.20, 76.70), 50.0)
        assertTrue("nearby sites must surface by location (no search)", near.isNotEmpty())
        assertTrue(
            "a distant region's sites must not surface",
            near.none { it.centroid.latitude > 40.0 }, // Annecy (~46N) must be excluded
        )
    }

    /**
     * **CLAIM K3 · Correct.** A site's name survives the download → cache → query
     * round-trip and resolves for the marker. Locks the nested-`properties.name`
     * path — without it, every PG-spot name reads "" and the layer renders nothing.
     */
    @Test
    fun `correct - a site name survives the round-trip and resolves for the marker`() {
        prefetch(
            "IN",
            site(32.20, 76.70, "Bir Launch"), site(32.22, 76.70, "Billing Landing"),
            site(32.24, 76.70, "Bir North"), site(32.26, 76.70, "Bir South"),
            site(32.28, 76.70, "Bir East"),
        )

        val served = cache.queryAllCachedNearby(GeoPoint(32.22, 76.70), 30.0)
        assertTrue("sites must be queryable from cache", served.isNotEmpty())

        val names = overlayFeaturesToGeoJson(served).features.mapNotNull {
            (it.properties?.get("name") as? JsonPrimitive)?.content
        }
        assertTrue("the site name must resolve for the marker (nested properties)", "Bir Launch" in names)
    }

    /**
     * **CLAIM K3 · Resilient.** Missing or corrupt site data degrades — never
     * crashes, never serves garbage. (Sites share the cache infra whose full fault
     * catalog is locked by the airspace tests; this confirms it holds for sites.)
     */
    @Test
    fun `resilient - missing or corrupt site cache degrades, never throws`() {
        // Missing region → graceful empty.
        assertTrue(
            "a missing site region must degrade to empty, not crash",
            cache.queryAllCachedNearby(GeoPoint(32.20, 76.70), 50.0).isEmpty(),
        )

        // Cache, then corrupt the on-disk index; a fresh instance must reject it.
        prefetch(
            "IN",
            site(32.20, 76.70, "A"), site(32.22, 76.70, "B"), site(32.24, 76.70, "C"),
            site(32.26, 76.70, "D"), site(32.28, 76.70, "E"),
        )
        java.io.File(java.io.File(tempFolder.root, "pgspots_cache"), "IN_pgspots.idx")
            .writeBytes(ByteArray(256) { it.toByte() })

        val fresh = PGSpotCache(context)
        assertFalse("a corrupt on-disk site index must not validate as cached", fresh.isCached("IN"))
    }
}
