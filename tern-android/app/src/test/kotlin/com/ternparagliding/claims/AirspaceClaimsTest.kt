package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.overlay.airspace.AirspaceGeoJson
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
     * **CLAIM K2 · Offline.** The pilot prefetches a region once at home (the only
     * time the network is touched). Then, with the network CUT for the whole
     * flight, every point along the corridor still has its airspace — served from
     * cache, with **zero network calls**. The offline-first promise.
     *
     * Checked across several real locations — both hemispheres, both longitude
     * signs, and near the antimeridian — so a location-dependent cache / Hilbert
     * index / haversine bug can't hide behind one lucky spot.
     */
    @Test
    fun `offline - airspace along the corridor is served from cache with zero network calls`() {
        listOf(
            Flight("IN", 32.20, 76.70, 0.0, 0.05, "Bir Billing (N hemi, +lon)"),
            Flight("FR", 45.90, 6.10, 0.05, 0.0, "Annecy (Europe)"),
            Flight("US", 40.00, -105.30, 0.0, 0.05, "Boulder (W hemi, -lon)"),
            Flight("NZ", -41.30, 174.78, -0.05, 0.0, "Wellington (S hemi)"),
            Flight("FJ", -17.80, 179.75, 0.0, 0.03, "near the antimeridian"),
        ).forEach { assertOfflineCorridorHolds(it) }
    }

    private data class Flight(
        val region: String, val lat: Double, val lon: Double,
        val dLat: Double, val dLon: Double, val label: String,
    )

    /** Prefetch a 6-point corridor once, cut the network, fly it: served + 0 calls. */
    private fun assertOfflineCorridorHolds(f: Flight) = runBlocking {
        // A realistic *cluster* (not one feature — a lone feature serialises below
        // the cache integrity size floor), placed along the corridor.
        val corridor = (0 until 6).map { i -> (f.lat + i * f.dLat) to (f.lon + i * f.dLon) }
        val airspaces = corridor.mapIndexed { i, (la, lo) -> airspaceBox(la, lo, "${f.region}-$i") }

        // The one network touch: pre-flight prefetch at home.
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            val emit = secondArg<(Map<String, Any>) -> Unit>()
            airspaces.forEach { emit(it) }
            true
        }
        assertTrue("${f.label}: prefetch should cache the region", cache.downloadAndCache(f.region))

        // Network CUT — any download attempt now fails the flight.
        var networkCalls = 0
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            networkCalls++
            throw IllegalStateException("network used in flight at ${f.label}")
        }

        corridor.forEach { (la, lo) ->
            assertTrue(
                "${f.label}: no airspace served offline at ($la, $lo)",
                cache.queryAllCachedNearby(GeoPoint(la, lo), 50.0 /* miles */).isNotEmpty(),
            )
        }
        assertEquals("${f.label}: the offline flight made network calls", 0, networkCalls)

        cache.clearCache() // isolate this location from the next
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

    /**
     * **CLAIM K2 · Correct.** A *real-shaped* OpenAIP airspace — a multi-vertex
     * Class-C TMA carrying OpenAIP's nested `properties` and `lowerLimit` /
     * `upperLimit` objects (not a synthetic box) — survives the real download →
     * parse → cache → query → class-resolve round-trip, and its class is read back
     * correctly. Locks the icaoClass 0-indexing against a realistic data shape.
     */
    @Test
    fun `correct - a real OpenAIP class C TMA caches, queries, and resolves its class`() = runBlocking {
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            secondArg<(Map<String, Any>) -> Unit>().invoke(realOpenAipClassCTma())
            true
        }
        assertTrue(cache.downloadAndCache("CH"))

        // Served from cache when a point inside the TMA is queried...
        val served = cache.queryAllCachedNearby(GeoPoint(46.10, 6.10), 30.0)
        assertTrue("a real OpenAIP airspace must be queryable from cache", served.isNotEmpty())

        // ...and the class resolves correctly from the real icaoClass (2 = C, 0-indexed).
        assertEquals(
            "OpenAIP icaoClass=2 must resolve to Class C",
            "C",
            AirspaceGeoJson.resolveAirspaceClass(served.first()),
        )
    }

    /**
     * A realistic OpenAIP airspace: a ~10-vertex Class-C TMA around the Geneva
     * basin, with OpenAIP's nested `properties` and `lowerLimit`/`upperLimit`
     * objects — the real data shape, not a 4-corner box.
     */
    private fun realOpenAipClassCTma(): Map<String, Any> = mapOf(
        "type" to "Feature",
        "properties" to mapOf(
            "name" to "GENEVA TMA",
            "icaoClass" to 2, // OpenAIP 0-indexed: 2 = Class C
            "type" to 0,
            "lowerLimit" to mapOf("value" to 8500, "unit" to 1, "referenceDatum" to 1),
            "upperLimit" to mapOf("value" to 19500, "unit" to 1, "referenceDatum" to 1),
        ),
        "geometry" to mapOf(
            "type" to "Polygon",
            "coordinates" to listOf(
                listOf(
                    listOf(6.00, 46.30), listOf(6.18, 46.34), listOf(6.34, 46.30),
                    listOf(6.40, 46.20), listOf(6.36, 46.08), listOf(6.22, 45.99),
                    listOf(6.05, 45.97), listOf(5.92, 46.05), listOf(5.90, 46.18),
                    listOf(6.00, 46.30),
                ),
            ),
        ),
    )
}
