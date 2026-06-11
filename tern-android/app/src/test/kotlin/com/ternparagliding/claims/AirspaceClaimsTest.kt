package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.utils.cache.AirspaceCache
import com.ternparagliding.utils.cache.GeoJsonUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.osmdroid.util.GeoPoint

/**
 * Claim **K2 · Airspace** — demonstrates the promises the airspace subsystem
 * makes to a pilot, by driving the real cache + query stack (no screenshots, no
 * emulator). See [docs/claims.md].
 *
 * The unit of testing is a claim, not a screen: we set up realistic conditions
 * (a cached region, then the network cut), fly a corridor, and assert observable
 * behavior — what the pilot's app actually has available.
 */
class AirspaceClaimsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cache: AirspaceCache

    @Before
    fun setUp() {
        context = mockk<Context>()
        every { context.cacheDir } returns tempFolder.root
        cache = AirspaceCache(context)
        mockkObject(GeoJsonUtils)
        every { GeoJsonUtils.isNdGeoJson(any()) } returns true
    }

    @After
    fun tearDown() {
        cache.clearCache()
        unmockkObject(GeoJsonUtils)
    }

    /** A small Class-B box airspace centred at (lat, lon). */
    private fun airspaceBox(lat: Double, lon: Double, name: String, half: Double = 0.05): Map<String, Any> =
        mapOf(
            "type" to "Feature",
            // icaoClass is 0-indexed (0=A, 1=B, …) per AirspaceGeoJson.resolveAirspaceClass.
            "properties" to mapOf("name" to name, "icaoClass" to 1),
            "geometry" to mapOf(
                "type" to "Polygon",
                "coordinates" to listOf(
                    listOf(
                        listOf(lon - half, lat - half),
                        listOf(lon + half, lat - half),
                        listOf(lon + half, lat + half),
                        listOf(lon - half, lat + half),
                        listOf(lon - half, lat - half),
                    ),
                ),
            ),
        )

    /**
     * **CLAIM K2 · Offline.** The pilot prefetches the region once at home (the
     * only time the network is touched). Then, with the network CUT for the whole
     * flight, every point along the corridor still has its airspace — served from
     * cache, with **zero network calls**. This is the offline-first promise: in
     * the air, nothing the pilot needs depends on connectivity.
     */
    @Test
    fun `offline - airspace along the corridor is served from cache with zero network calls`() = runBlocking {
        // ── Arrange: pre-flight prefetch (the one time we use the network) ──
        // A corridor of Class-B airspaces along the Bir Billing ridge
        // (~32.2N, heading east). A realistic *cluster*, not one feature — a
        // single feature serialises below the cache integrity size floor.
        val corridor: List<Pair<Double, Double>> = (0 until 10).map { i -> 32.20 to (76.70 + i * 0.05) }
        val airspaces = corridor.mapIndexed { i, (lat, lon) -> airspaceBox(lat, lon, "BIR-$i") }

        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            val emit = secondArg<(Map<String, Any>) -> Unit>()
            airspaces.forEach { emit(it) }
            true
        }
        assertTrue("pre-flight prefetch should cache the region", cache.downloadAndCache("IN"))

        // ── Act + Assert: the network is now CUT — any download attempt fails ──
        var networkCalls = 0
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            networkCalls++
            throw IllegalStateException("network used in flight — offline claim broken")
        }

        // Fly the corridor point by point; every viewport must have airspace.
        corridor.forEach { (lat, lon) ->
            val inView = cache.queryAllCachedNearby(GeoPoint(lat, lon), 50.0 /* miles */)
            assertTrue("no airspace served offline at ($lat, $lon)", inView.isNotEmpty())
        }

        assertEquals("the offline flight made network calls", 0, networkCalls)
    }

    /**
     * **CLAIM K2 · Resilient.** Missing, stale, or corrupt airspace data must
     * DEGRADE — never crash, never serve garbage. The canonical "never breaks,
     * only degrades" case.
     *
     * Note: an already-loaded region keeps serving from its in-memory index, so
     * the on-disk corruption path is the one a *fresh app launch* hits — we
     * exercise it with a fresh cache instance.
     */
    @Test
    fun `resilient - missing or corrupt airspace cache degrades, never throws`() = runBlocking {
        // (a) Missing region (nothing cached) → graceful empty, not a crash.
        assertTrue(
            "a missing region must degrade to empty, not crash",
            cache.queryAllCachedNearby(GeoPoint(32.20, 76.70), 50.0).isEmpty(),
        )

        // Cache a region, then corrupt its on-disk index with plausibly-sized
        // garbage (above the integrity size floor, so the header parse is actually
        // exercised — not just a too-small short-circuit).
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            secondArg<(Map<String, Any>) -> Unit>().invoke(airspaceBox(32.20, 76.70, "BIR-0"))
            true
        }
        assertTrue(cache.downloadAndCache("IN"))
        // SpatialDiskCache stores under context.cacheDir/airspace_cache/.
        val airspaceCacheDir = java.io.File(tempFolder.root, "airspace_cache")
        java.io.File(airspaceCacheDir, "IN_airspace.idx").writeBytes(ByteArray(256) { it.toByte() })

        // (b) A FRESH cache instance (no in-memory state) reads the corrupt index
        //     off disk, exactly as a real app launch would. It must reject it as
        //     not-cached and never throw.
        val freshCache = AirspaceCache(context)
        val cachedOnFreshRead = freshCache.isCached("IN") // must not throw
        assertFalse("a corrupt on-disk index must not validate as cached", cachedOnFreshRead)
    }
}
