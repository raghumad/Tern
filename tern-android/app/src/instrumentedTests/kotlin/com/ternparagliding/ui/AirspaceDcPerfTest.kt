package com.ternparagliding.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ternparagliding.overlay.airspace.AirspaceBuildProbe
import com.ternparagliding.redux.MapAction
import com.ternparagliding.utils.TestCacheInjector
import com.ternparagliding.utils.cache.CacheManager
import com.ternparagliding.utils.cache.MapOverlayCacheUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Human-faithful reproduction of the "Washington DC airspaces repaint very
 * slowly" regression, on the real-clock [RealUserMapTest] harness.
 *
 * Why this can catch what the ComposeTestRule tests can't: the lag only appears
 * under a **real frame clock** with a **dense, heavy** overlay (DC has ~168
 * airspaces, including many-vertex SFRA shapes). When the previous commit just
 * handed MapLibre that many polygons to rasterize, the overlay's off-thread
 * query coroutine — if it resumes on the frame-clock dispatcher — waits seconds
 * for a frame slot before the NEXT pan's result can land. We reproduce that by
 * injecting a dense cluster of 80-vertex polygons and driving real finger drags,
 * measuring the latency from each drag to the overlay actually committing fresh
 * features ([AirspaceBuildProbe]). The fix (collect on Dispatchers.Default)
 * should collapse that latency.
 */
@RunWith(AndroidJUnit4::class)
class AirspaceDcPerfTest : RealUserMapTest() {

    private companion object {
        const val LAT = 38.9
        const val LON = -100.0          // central US, over land (no ocean to confound pixels).
        const val ZOOM = 9.0

        const val CLUSTER = 160         // ~DC airspace density.
        const val VERTICES = 80         // many-vertex polygons → real rasterize cost.
        const val DRAGS = 8             // measured pan nudges.
        const val PER_DRAG_BUDGET_MS = 4_000L // overlay must repaint within this after a drag.
        const val WAIT_TIMEOUT_MS = 35_000L   // generous so the buggy build's 10-26 s is recorded, not cut off.
    }

    @Test
    fun denseAirspaceRepaintsPromptlyAfterRealDrags() {
        // Arrange: a dense, heavy airspace cluster around the start centre.
        val cluster = buildList {
            var i = 0
            // ~13x13 jittered grid of heavy polygons within ±0.8° of centre.
            var dlat = -0.8
            while (dlat <= 0.8) {
                var dlon = -0.8
                while (dlon <= 0.8) {
                    add(heavyAirspace(LAT + dlat, LON + dlon, radiusDeg = 0.12, vertices = VERTICES, name = "A$i"))
                    i++
                    dlon += 0.13
                }
                dlat += 0.13
            }
        }.take(CLUSTER)

        // Launch FIRST (initializes + clears CacheManager), THEN inject — otherwise
        // launchOnMap's clearAllCaches would wipe the fixtures, and CacheManager
        // isn't initialized until launch.
        launchOnMap(LAT, LON - 0.4, ZOOM)
        TestCacheInjector.injectAirspaces(targetContext, CacheManager.airspaceCache, "US", cluster)
        onStore { it.dispatch(MapAction.AddAirspaceCountry("US")) }
        AirspaceBuildProbe.buildCount = 0
        AirspaceBuildProbe.lastFeatureCount = 0

        // Warm up: one real drag to bring the cluster under the centre and let the
        // first (heavy) commit + render happen.
        dragMap(0.70, 0.5, 0.40, 0.5, steps = 60)
        waitForFreshBuild(baseline = 0, timeoutMs = WAIT_TIMEOUT_MS)
        Thread.sleep(1500)

        // The render must actually be visible (proves the real-clock harness drives
        // the GPU overlay that the gesture path can't under ComposeTestRule).
        val shot = screenshot()
        val blue = bluePixels(shot, centralBox(shot))
        saveScreenshot("dc_perf_dense_airspace")
        assertTrue("Dense airspace did not paint after a real drag (blue=$blue)", blue >= 500)

        // Measure: latency from each real drag to the overlay committing fresh data.
        val latencies = ArrayList<Long>()
        var east = true
        repeat(DRAGS) {
            val baseline = AirspaceBuildProbe.buildCount
            // Small nudge that keeps the dense cluster in view (>2 km → re-query),
            // alternating direction so we stay over the cluster.
            if (east) dragMap(0.62, 0.5, 0.44, 0.5, steps = 50)
            else dragMap(0.44, 0.5, 0.62, 0.5, steps = 50)
            east = !east
            val ms = waitForFreshBuild(baseline, WAIT_TIMEOUT_MS)
            latencies.add(ms)
            android.util.Log.w("AirspaceDcPerfTest", "leg repaint latency = ${ms}ms (features=${AirspaceBuildProbe.lastFeatureCount})")
            Thread.sleep(400)
        }

        val sorted = latencies.filter { it >= 0 }.sorted()
        val median = if (sorted.isEmpty()) Long.MAX_VALUE else sorted[sorted.size / 2]
        val worst = latencies.maxOrNull() ?: -1
        android.util.Log.w(
            "AirspaceDcPerfTest",
            "repaint latencies (ms) = $latencies  median=$median worst=$worst  candidates~${AirspaceBuildProbe.lastFeatureCount}",
        )

        assertTrue(
            "Overlay repaint after a drag is too slow over dense airspace: median=${median}ms " +
                "worst=${worst}ms (budget ${PER_DRAG_BUDGET_MS}ms). latencies=$latencies",
            median in 0..PER_DRAG_BUDGET_MS,
        )
    }

    /** Polls the process-global build probe; returns ms until a fresh commit, or -1 on timeout. */
    private fun waitForFreshBuild(baseline: Int, timeoutMs: Long): Long {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (AirspaceBuildProbe.buildCount > baseline && AirspaceBuildProbe.lastFeatureCount >= 1) {
                return System.currentTimeMillis() - t0
            }
            Thread.sleep(50)
        }
        return -1
    }

    /** A Class-D airspace as an [vertices]-gon (≈ a circle) — many vertices so MapLibre pays a real rasterize cost. */
    private fun heavyAirspace(
        lat: Double,
        lon: Double,
        radiusDeg: Double,
        vertices: Int,
        name: String,
    ): MapOverlayCacheUtils.OverlayFeature {
        val ring = ArrayList<List<Double>>(vertices + 1)
        for (i in 0..vertices) {
            val a = 2.0 * Math.PI * i / vertices
            ring.add(listOf(lon + radiusDeg * cos(a), lat + radiusDeg * sin(a)))
        }
        val featureMap = mapOf(
            "type" to "Feature",
            "properties" to mapOf("name" to name, "class" to "D"),
            "geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(ring)),
        )
        val centroid = GeoPoint(lat, lon)
        return MapOverlayCacheUtils.OverlayFeature(
            feature = featureMap,
            centroid = centroid,
            hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(centroid, 16),
            overlayType = "airspace",
        )
    }
}
