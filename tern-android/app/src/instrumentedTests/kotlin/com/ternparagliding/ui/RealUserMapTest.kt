package com.ternparagliding.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.cache.CacheManager
import org.junit.After
import org.osmdroid.util.GeoPoint

/**
 * Base for **human-faithful** map tests.
 *
 * Unlike [com.ternparagliding.utils.MapVisualTest], this does NOT use
 * ComposeTestRule. It launches the real activity under its **real Choreographer
 * frame clock** (via [ActivityScenario]) and drives it the way a finger does —
 * OS-level touch through [UiDevice], which reaches BOTH Compose nodes and the
 * MapLibre GL surface (Compose `performTouchInput` reaches neither the GL
 * surface nor, here, the map). Observation is by hardware screenshot — what a
 * human actually sees.
 *
 * Why a separate base: ComposeTestRule controls the frame clock and idle-sync,
 * which (a) starves the overlay's off-thread snapshot-flow render during
 * gestures and (b) decouples the camera from programmatic zoom — so it cannot
 * faithfully reproduce real-time behaviour like the "dense DC repaints slowly"
 * regression. Running the real clock can.
 *
 * Discipline: **Arrange** with fixtures (inject cache, position the camera once),
 * **Act** only through [UiDevice] (real touch), **Assert** on pixels (and the
 * in-process build probe as a precise latency signal).
 */
abstract class RealUserMapTest {

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected val targetContext get() = instrumentation.targetContext

    protected lateinit var scenario: ActivityScenario<TernParaglidingActivity>

    @After
    fun closeScenario() {
        if (this::scenario.isInitialized) {
            try { scenario.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    // ── Arrange + launch ────────────────────────────────────────────────────

    /**
     * Grant location, isolate the cache, then launch the real activity and wait
     * for the basemap to actually paint. Sets a TEST country so no network
     * download races the injected fixtures.
     */
    protected fun launchOnMap(lat: Double, lon: Double, zoom: Double = 9.0) {
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.ACCESS_COARSE_LOCATION)

        com.ternparagliding.utils.geo.CountryUtils.setTestCountryCode("TEST")
        CacheManager.initialize(targetContext.applicationContext)
        CacheManager.clearAllCaches()

        scenario = ActivityScenario.launch(TernParaglidingActivity::class.java)

        // Position the camera once (Arrange — not the action under test) and mark
        // location ready so the welcome screen clears and auto-centre stops fighting us.
        onStore { store ->
            store.dispatch(MapAction.SetLocationReady(true))
            store.dispatch(MapAction.UpdateCenter(GeoPoint(lat, lon)))
            store.dispatch(MapAction.UpdateZoom(zoom))
        }
        check(waitForBasemap()) { "Basemap never painted after launch" }
        assertNoPairingFailure()
    }

    /**
     * Honest-test guard: fail if the "Pairing failed" overlay (board-not-found)
     * is on screen — the map is then hidden behind it and no assertion below is
     * valid. Mirrors MapVisualTest.assertNoPairingFailureOnScreen for the
     * UiDevice (real-clock) harness.
     */
    protected fun assertNoPairingFailure() {
        val o = device.findObject(androidx.test.uiautomator.By.textContains("Pairing failed"))
        if (o != null) {
            throw AssertionError("App is showing the 'Pairing failed' overlay — map/UI not in a valid state.")
        }
    }

    /** Run [block] with the live [MapStore] on the UI thread. */
    protected fun onStore(block: (MapStore) -> Unit) {
        scenario.onActivity { activity: Activity ->
            val store = ViewModelProvider(activity as TernParaglidingActivity)[MapStore::class.java]
            block(store)
        }
        instrumentation.waitForIdleSync()
    }

    protected fun currentCenter(): GeoPoint {
        var c: GeoPoint? = null
        onStore { c = it.state.value.center }
        return c ?: error("map centre is null")
    }

    // ── Act (real OS-level touch) ───────────────────────────────────────────

    private val w get() = device.displayWidth
    private val h get() = device.displayHeight

    /**
     * A real, continuous finger drag given as fractions of the screen (0..1).
     * [steps] controls duration/smoothness (more = slower). Reaches the GL surface.
     */
    protected fun dragMap(fromX: Double, fromY: Double, toX: Double, toY: Double, steps: Int = 60) {
        device.swipe((w * fromX).toInt(), (h * fromY).toInt(), (w * toX).toInt(), (h * toY).toInt(), steps)
        device.waitForIdle()
    }

    /** A real tap at a screen fraction. */
    protected fun tapMap(fx: Double, fy: Double) {
        device.click((w * fx).toInt(), (h * fy).toInt())
        device.waitForIdle()
    }

    // ── Observe (what a human sees) ─────────────────────────────────────────

    protected fun screenshot(): Bitmap =
        instrumentation.uiAutomation.takeScreenshot() ?: error("screenshot returned null")

    /** Central 60% of the frame — where the camera centre sits. */
    protected fun centralBox(shot: Bitmap): Rect =
        Rect(shot.width / 5, shot.height / 5, shot.width * 4 / 5, shot.height * 4 / 5)

    /** Blue-dominant pixels — the translucent airspace fill + border signature. */
    protected fun bluePixels(shot: Bitmap, rect: Rect, margin: Int = 45, minBlue: Int = 50): Int {
        var n = 0
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val p = shot.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (b >= minBlue && b - r >= margin && b - g >= margin) n++
                x += 2
            }
            y += 2
        }
        return n
    }

    private fun meanLuminance(shot: Bitmap, rect: Rect): Double {
        var sum = 0.0; var n = 0
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val p = shot.getPixel(x, y)
                sum += 0.3 * Color.red(p) + 0.59 * Color.green(p) + 0.11 * Color.blue(p)
                n++; x += 10
            }
            y += 10
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Block until the raster basemap has painted (not the black "no tiles" state). */
    protected fun waitForBasemap(timeoutMs: Long = 60_000, minLum: Double = 70.0): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val shot = screenshot()
            if (meanLuminance(shot, centralBox(shot)) >= minLum) return true
            Thread.sleep(1000)
        }
        return false
    }

    protected fun saveScreenshot(name: String) {
        try {
            val dir = java.io.File("/sdcard/Pictures/realuser")
            if (!dir.exists()) dir.mkdirs()
            java.io.FileOutputStream(java.io.File(dir, "$name.png")).use {
                screenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
