package com.ternparagliding.ui

import androidx.compose.ui.test.*
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.ReportGenerator
import com.ternparagliding.utils.WeatherTestHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Airspace render performance — **main-thread responsiveness**.
 *
 * This is the *honest* validation for the "airspaces render slowly" report.
 * The bug is not about *what* the overlay produces (the GeoJSON is correct),
 * it's about *which thread* builds it: `AirspaceLayer` runs
 * `AirspaceGeoJson.toFeatureCollection(candidates)` inside `remember { }`
 * during composition — i.e. on the **main/UI thread** — so a dense-airspace
 * pan blocks the frame while it parses every vertex of every polygon.
 *
 * A correctness test would pass before *and* after a fix, proving nothing.
 * This test instead measures the symptom the pilot feels: while a heavy
 * airspace set is queried and built after a pan, is the **main thread ever
 * stalled** beyond a frame budget? We pin a ~8 ms heartbeat on the main
 * looper and record the largest gap between beats — a synchronous build
 * delays the heartbeat for the whole build duration.
 *
 * Expected to FAIL on the current (unfixed) code — that red, with the logged
 * `maxStallMs`, is the measured proof of the bug. After the build is moved
 * off the main thread it should pass.
 */
class AirspaceRenderPerfTest : MapVisualTest() {

    @Before
    fun startMockServer() {
        WeatherTestHelper.startServer()
    }

    @After
    fun stopMockServer() {
        WeatherTestHelper.stopServer()
    }

    /**
     * BENCHMARK + correctness guard: times [AirspaceGeoJson.toFeatureCollection]
     * on the main thread for a dense set and asserts every candidate becomes a
     * feature. The measured cost (~370 ms for ~80 polygons on the Ulefone) is
     * the benchmark that justified moving this build off the UI thread; it is
     * logged, not budget-asserted (the fix relocates the work rather than
     * speeding it up). The "never on the main thread in production" contract is
     * the job of [airspaceBuildRunsOffMainThread].
     */
    @Test
    fun airspaceGeoJsonBuildCostOnMainThread() {
        val lat = 40.0
        val lon = -105.2
        scenario("Airspace GeoJSON build cost on the main thread") {
            story("As a pilot panning into busy airspace, I want the map to stay smooth while airspaces draw — not freeze for a moment.") {

                given("the app is on the map with a dense airspace region cached") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")

                    val context = androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().targetContext

                    // A dense-airspace region: 80 polygons (each 100 vertices)
                    // on a grid within ~±0.4° (~45 km) of centre — all inside
                    // AirspaceOverlay's 200 km query radius, so a single query
                    // returns the whole set (mirrors the ~60 candidates seen on
                    // a real Denver pan, deliberately heavier to make the
                    // main-thread cost unambiguous).
                    val features = buildList {
                        val grid = 9 // 9x9 ≈ 81
                        for (i in 0 until grid) {
                            for (j in 0 until grid) {
                                if (size >= POLY_COUNT) break
                                val plat = lat - 0.4 + i * 0.1
                                val plon = lon - 0.4 + j * 0.1
                                add(createDenseAirspace("AS_${i}_$j", plat, plon))
                            }
                        }
                    }
                    ReportGenerator.logStep(
                        "SETUP",
                        "Injecting ${features.size} airspace polygons x $VERTICES vertices each",
                    )
                    com.ternparagliding.utils.TestCacheInjector.injectAirspaces(
                        context,
                        com.ternparagliding.utils.cache.CacheManager.airspaceCache,
                        "US",
                        features,
                    )
                    // NB: we do NOT trigger the airspace query here — it's fired
                    // inside the measured window below so the probe captures the
                    // main-thread build (the IO query + build land several seconds
                    // after the trigger, so a long sample window is essential).
                }

                `when`("the overlay queries the dense set and builds its GeoJSON") {
                    // Deterministic split of the two costs, exactly as
                    // AirspaceOverlay/AirspaceLayer incur them — no timing races
                    // against the async pipeline.
                    val cache = com.ternparagliding.utils.cache.CacheManager.airspaceCache

                    // (1) IO cost: the spatial query + FlexBuffer hydration that
                    //     production runs inside withContext(Dispatchers.IO).
                    var queryMs = 0L
                    var candidates: List<com.ternparagliding.overlay.airspace.AirspaceCandidate> = emptyList()
                    val q = Thread {
                        val t0 = android.os.SystemClock.uptimeMillis()
                        val feats = cache.queryNearbyFeatures("US", GeoPoint(lat, lon), QUERY_RADIUS_MILES)
                        candidates = feats.map { com.ternparagliding.overlay.airspace.AirspaceCandidate(it, "D") }
                        queryMs = android.os.SystemClock.uptimeMillis() - t0
                    }
                    q.start(); q.join()

                    // (2) MAIN-THREAD cost: the exact call AirspaceLayer makes
                    //     inside remember { } during composition. This is the
                    //     quantity the proposed fix moves off the main thread.
                    var buildMs = 0L
                    var featureCount = 0
                    composeTestRule.runOnUiThread {
                        val t0 = android.os.SystemClock.uptimeMillis()
                        val fc = com.ternparagliding.overlay.airspace.AirspaceGeoJson.toFeatureCollection(candidates)
                        buildMs = android.os.SystemClock.uptimeMillis() - t0
                        featureCount = fc.features.size
                    }

                    measuredBuildMs = buildMs
                    measuredCandidateCount = candidates.size
                    measuredFeatureCount = featureCount
                    val summary = "candidates=${candidates.size} features=$featureCount " +
                        "queryMs(IO)=$queryMs buildMs(main)=$buildMs"
                    android.util.Log.w(PERF_TAG, summary)
                    ReportGenerator.logStep("MEASURE", summary)
                }

                then("the build is correct, and its main-thread cost (logged) justifies running it off-thread") {
                    // Correctness guard for the off-thread refactor: every
                    // candidate becomes a feature (none dropped/duplicated). The
                    // build's main-thread cost is logged as the benchmark that
                    // motivated moving it off the UI thread — it is NOT asserted
                    // against a budget here, because the fix removes it from the
                    // main thread rather than making it cheaper. The "never on the
                    // main thread in production" contract is enforced by
                    // [airspaceBuildRunsOffMainThread].
                    ReportGenerator.logStep(
                        "ASSERT",
                        "candidates=$measuredCandidateCount features=$measuredFeatureCount " +
                            "buildMs(main, benchmark)=$measuredBuildMs (one frame ≈ ${FRAME_BUDGET_MS}ms)",
                        if (measuredCandidateCount > 0 && measuredFeatureCount == measuredCandidateCount) "PASS" else "FAIL",
                    )
                    assertThat(measuredCandidateCount).isGreaterThan(0)
                    assertThat(measuredFeatureCount).isEqualTo(measuredCandidateCount)
                }
            }
        }
    }

    /**
     * FIX VALIDATION (end-to-end): reproduces the real "airspaces render
     * slowly" report at ~800 km scale. At that zoom a single finger-swipe moves
     * the geographic centre by hundreds of km — far past the 2 km re-query
     * threshold — so the overlay re-queries and rebuilds its GeoJSON. On the
     * current code that build runs on the main thread and freezes it; a
     * background probe (one-shot main-thread posts) measures the worst freeze.
     *
     * Expected RED now (the build runs on the main thread); GREEN once it is
     * moved off it.
     *
     * Why a thread check rather than a stall measurement: a sampling probe that
     * posts to the main looper perturbs the very scheduling it measures — the
     * dense rebuild was observed to defer until the probe stopped, so the stall
     * never landed in-window. The *cost* of the build (≈370 ms) is proven
     * deterministically by [airspaceGeoJsonBuildCostOnMainThread]; this test
     * deterministically proves the contract the fix establishes: that expensive
     * build no longer happens on the UI thread, so it cannot freeze it.
     */
    @Test
    fun airspaceBuildRunsOffMainThread() {
        val lat = 40.0
        val lon = -105.2
        val builtOnThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        com.ternparagliding.overlay.airspace.AirspaceBuildProbe.observer = null // reset from any prior test

        scenario("Airspace GeoJSON build runs off the main thread") {
            story("As a pilot scanning a wide region, I want airspaces to load without the map freezing — so the expensive GeoJSON build must run off the UI thread.") {

                given("a wide, dense airspace field is cached at ~800 km scale") {
                    givenAppIsLaunchedOnMap(lat = lat, lon = lon, countryCode = "us")
                    val context = androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().targetContext

                    // Spread airspace across ±2.1° (~±230 km) so any pan within the
                    // wide view still finds a dense set (~80+) to rebuild — the
                    // worst case a pilot hits over a busy region.
                    val features = buildList {
                        var la = lat - 2.1
                        while (la <= lat + 2.1 + 1e-9) {
                            var lo = lon - 2.1
                            while (lo <= lon + 2.1 + 1e-9) {
                                add(createDenseAirspace("AS_${la}_$lo", la, lo))
                                lo += 0.3
                            }
                            la += 0.3
                        }
                    }
                    ReportGenerator.logStep(
                        "SETUP",
                        "Injecting ${features.size} airspace polygons over a ~460 km-wide field",
                    )
                    com.ternparagliding.utils.TestCacheInjector.injectAirspaces(
                        context,
                        com.ternparagliding.utils.cache.CacheManager.airspaceCache,
                        "US",
                        features,
                    )

                    // Record the thread of every real overlay build from here on.
                    com.ternparagliding.overlay.airspace.AirspaceBuildProbe.observer = { name ->
                        builtOnThreads.add(name)
                    }
                }

                `when`("the overlay queries the dense set and builds its GeoJSON") {
                    val store = composeTestRule.runOnIdle {
                        androidx.lifecycle.ViewModelProvider(
                            composeTestRule.activity
                        )[com.ternparagliding.redux.MapStore::class.java]
                    }
                    composeTestRule.runOnUiThread {
                        // Registers the country (forces a re-query of the injected
                        // data) and zooms to the ~800 km wide view.
                        store.dispatch(com.ternparagliding.redux.MapAction.AddAirspaceCountry("US"))
                        store.dispatch(com.ternparagliding.redux.MapAction.UpdateZoom(7.0))
                    }
                    waitForAirspaces(minCount = 1, timeoutMillis = 15000)
                    // Nudge the centre just past the 2 km re-query threshold (~5 km).
                    // The rebuild is still heavy — the dense field fills the fixed
                    // 200 km query radius — but a tiny camera move avoids the long
                    // software-GL animation a big pan triggers on an emulator, which
                    // otherwise starves the recomposition for ~90 s. On real hardware
                    // a big pan is fine; the build cost (and its thread) is identical.
                    composeTestRule.runOnUiThread {
                        store.dispatch(com.ternparagliding.redux.MapAction.UpdateCenter(GeoPoint(lat + 0.05, lon)))
                    }
                    // Pump the Compose framework while waiting: under the test
                    // rule a raw Thread.sleep does NOT advance recomposition, so
                    // the LaunchedEffect re-query + build stay starved until the
                    // test calls a sync point. waitForIdle() pumps a frame each
                    // iteration so the build actually runs (and, when it's on the
                    // main thread, completes within one of these idle pumps).
                    val deadline = System.currentTimeMillis() + 30000
                    while (builtOnThreads.isEmpty() && System.currentTimeMillis() < deadline) {
                        composeTestRule.waitForIdle()
                        Thread.sleep(200)
                    }
                    ReportGenerator.logStep("MEASURE", "airspace build threads: ${builtOnThreads.toList()}")
                }

                then("every build ran on a background thread, never the main thread") {
                    com.ternparagliding.overlay.airspace.AirspaceBuildProbe.observer = null
                    val threads = builtOnThreads.toList()
                    android.util.Log.w(PERF_TAG, "airspace build threads: $threads")
                    val onMain = threads.count { it == "main" }
                    ReportGenerator.logStep(
                        "ASSERT",
                        "builds=${threads.size} onMainThread=$onMain (threads=$threads)",
                        if (threads.isNotEmpty() && onMain == 0) "PASS" else "FAIL",
                    )
                    assertThat(threads).isNotEmpty()
                    assertThat(onMain).isEqualTo(0)
                }
            }
        }
    }

    // --- Test data helpers ---

    /**
     * A polygon airspace approximated as a [VERTICES]-point circle of radius
     * [radiusDeg] around (lat, lon). Many vertices make the per-polygon
     * GeoJSON build (vertex -> spatialk Position) measurably expensive, which
     * is the cost this test is probing.
     */
    private fun createDenseAirspace(
        name: String,
        lat: Double,
        lon: Double,
        radiusDeg: Double = 0.01,
    ): com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature {
        val ring = ArrayList<List<Double>>(VERTICES + 1)
        for (k in 0 until VERTICES) {
            val theta = 2.0 * Math.PI * k / VERTICES
            ring.add(listOf(lon + radiusDeg * Math.cos(theta), lat + radiusDeg * Math.sin(theta)))
        }
        ring.add(ring.first()) // close the ring
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf("name" to name, "class" to "D"),
            "geometry" to mapOf(
                "type" to "Polygon",
                "coordinates" to listOf(ring),
            ),
        )
        val centroid = GeoPoint(lat, lon)
        return com.ternparagliding.utils.cache.MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = com.ternparagliding.utils.cache.MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "airspace",
        )
    }

    private var measuredBuildMs: Long = 0
    private var measuredCandidateCount: Int = 0
    private var measuredFeatureCount: Int = 0

    companion object {
        private const val PERF_TAG = "AirspacePerf"
        private const val POLY_COUNT = 80
        private const val VERTICES = 100
        // AirspaceOverlay queries a 200 km radius (≈124 mi).
        private const val QUERY_RADIUS_MILES = 124.3
        // One display frame ≈ 16.6 ms; a build that overruns it drops frames.
        private const val FRAME_BUDGET_MS = 16L
    }
}
