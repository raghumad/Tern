package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.overlay.airspace.AirspaceGeoJson
import com.ternparagliding.overlay.priority.OverlayKind
import com.ternparagliding.overlay.priority.OverlayPrioritizer
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.overlay.priority.SimpleOverlayCandidate
import com.ternparagliding.utils.cache.AirspaceCache
import com.ternparagliding.utils.cache.PGSpotCache
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /** Cache a 5-feature cluster under [region] at (lat, lon). Returns the cache subdir. */
    private fun cacheClusterAt(region: String, lat: Double, lon: Double): java.io.File = runBlocking {
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            val emit = secondArg<(Map<String, Any>) -> Unit>()
            (0 until 5).forEach { i -> emit(airspaceBox(lat + i * 0.03, lon, "$region-$i")) }
            true
        }
        assertTrue("$region: prefetch should cache", cache.downloadAndCache(region))
        java.io.File(tempFolder.root, "airspace_cache")
    }

    /**
     * **CLAIM K2 · Resilient (truncated data).** An interrupted download leaves a
     * half-written `.flex`. The query must degrade (partial/empty) — never crash.
     */
    @Test
    fun `resilient - a truncated data file does not crash the query`() {
        val dir = cacheClusterAt("IN", 32.20, 76.70)
        val flex = java.io.File(dir, "IN_airspace.flex")
        val bytes = flex.readBytes()
        flex.writeBytes(bytes.copyOf(maxOf(120, bytes.size / 2))) // simulate interrupted write

        val fresh = AirspaceCache(context) // on-disk read path, as a real launch
        try {
            fresh.queryAllCachedNearby(GeoPoint(32.20, 76.70), 50.0) // must not throw
        } catch (t: Throwable) {
            fail("a truncated data file crashed the query: $t")
        }
    }

    /**
     * **CLAIM K2 · Resilient (half-deleted).** The index survives but the data file
     * is gone. The region must be refused as not-cached and degrade to empty.
     */
    @Test
    fun `resilient - a missing data file with a present index is refused, never crashes`() {
        val dir = cacheClusterAt("IN", 32.20, 76.70)
        assertTrue(java.io.File(dir, "IN_airspace.flex").delete())

        val fresh = AirspaceCache(context)
        assertFalse("a region whose data file vanished must not validate as cached", fresh.isCached("IN"))
        try {
            assertTrue(
                "a half-deleted region must degrade to empty",
                fresh.queryAllCachedNearby(GeoPoint(32.20, 76.70), 50.0).isEmpty(),
            )
        } catch (t: Throwable) {
            fail("a missing data file crashed the query: $t")
        }
    }

    /**
     * **CLAIM K2 · Resilient (stale).** A cache older than the freshness window
     * (90 days) must be refused — never silently serve a pilot stale airspace.
     */
    @Test
    fun `resilient - a stale cache beyond the freshness window is refused, not silently served`() {
        val dir = cacheClusterAt("IN", 32.20, 76.70)
        // Rewrite the on-disk cache index with a 100-day-old timestamp.
        val staleMs = System.currentTimeMillis() - 100L * 24 * 3600 * 1000
        java.io.ObjectOutputStream(java.io.FileOutputStream(java.io.File(dir, "cache_index"))).use {
            it.writeObject(java.util.HashMap<String, Long>().apply { put("IN", staleMs) })
        }

        val fresh = AirspaceCache(context)
        assertFalse(
            "a 100-day-old cache must be refused as stale, not silently served",
            fresh.isCached("IN"),
        )
    }

    /**
     * **CLAIM K2 · Resilient (no cascade).** A fault in one known must not break
     * another. Airspace and PG spots live in separate caches; corrupting the
     * airspace cache must leave PG-spot queries completely unaffected. Locks the
     * isolation so a future refactor can't let one subsystem poison another.
     */
    @Test
    fun `resilient - an airspace cache fault does not cascade to PG spots`() = runBlocking {
        val pgCache = PGSpotCache(context)

        // Cache airspace...
        coEvery { GeoJsonUtils.streamGeoJsonFeatures(any(), any()) } answers {
            val emit = secondArg<(Map<String, Any>) -> Unit>()
            (0 until 5).forEach { i -> emit(airspaceBox(32.20 + i * 0.03, 76.70, "IN-$i")) }
            true
        }
        assertTrue(cache.downloadAndCache("IN"))

        // ...and PG spots — a different known, a separate cache. PGSpotCache uses
        // the downloadGeoJson string seam (not the streaming one airspace uses);
        // isNdGeoJson is mocked true, so feed it newline-delimited features.
        val pgNd = (0 until 5).joinToString("\n") { i ->
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[76.72,${32.20 + i * 0.02}]},"properties":{"name":"SPOT-$i","id":$i}}"""
        }
        coEvery { GeoJsonUtils.downloadGeoJson(any(), any()) } returns pgNd
        assertNotNull("PG spots should cache", pgCache.downloadAndCache("IN"))

        // A FAULT in airspace: corrupt its on-disk index.
        java.io.File(java.io.File(tempFolder.root, "airspace_cache"), "IN_airspace.idx")
            .writeBytes(ByteArray(256) { it.toByte() })

        // No cascade: the other known (PG spots) is entirely unaffected.
        assertTrue(
            "a PG-spot query must survive an airspace cache fault (no cascade)",
            pgCache.queryNearbyPGSpots("IN", GeoPoint(32.20, 76.72), 50.0).isNotEmpty(),
        )

        pgCache.clearCache()
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
     * **CLAIM K2 · Frictionless (memory-safe declutter).** No matter how dense the
     * airspace, the overlay stays bounded by the budget — so it can never OOM, the
     * way dragging into dense Europe (Annecy) did *before* budgeting was added —
     * and it keeps the *nearest* (most relevant) airspace. Under memory pressure
     * the budget tightens further, never grows.
     *
     * This is the guarantee the old on-device "performance scorecard" was chasing,
     * now a deterministic assertion instead of a flaky memory measurement.
     */
    @Test
    fun `frictionless - dense airspace is bounded by the budget and keeps the nearest`() {
        val prioritizer = OverlayPrioritizer() // default budget 300
        val pilot = Position(45.90, 6.10)      // Annecy

        // A pathologically dense region — the kind that OOM'd before budgeting.
        // Each successive airspace is farther from the pilot.
        val dense = (1..3000).map { i ->
            SimpleOverlayCandidate(
                kind = OverlayKind.AIRSPACE,
                position = Position(45.90 + i * 0.001, 6.10),
                id = "asp-$i",
            )
        }

        val shown = prioritizer.prioritize(dense, pilot)

        // (1) Memory stays bounded — 3000 candidates in, at most `budget` out.
        assertEquals("dense airspace must be capped to the budget (the OOM guard)", 300, shown.size)

        // (2) Declutter is sensible — the nearest is kept, the farthest dropped.
        val keptIds = shown.filterIsInstance<SimpleOverlayCandidate>().map { it.id }.toSet()
        assertTrue("the nearest airspace must be kept", "asp-1" in keptIds)
        assertFalse("a far airspace must be decluttered out", "asp-3000" in keptIds)

        // (3) Under memory pressure (onTrimMemory halves the budget) the overlay
        //     must shrink, never grow.
        prioritizer.budget = 150
        assertEquals(
            "under memory pressure the overlay must shrink, never grow",
            150,
            prioritizer.prioritize(dense, pilot).size,
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
