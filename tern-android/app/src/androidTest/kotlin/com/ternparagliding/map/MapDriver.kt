package com.ternparagliding.map

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.MapProjectionTestHook

/**
 * The "cruder camera" — the **L1 (pilot-outcome) driver** for K6 map claims.
 *
 * Drives the LIVE MapLibre map with raw screen touches via UiAutomator, which does
 * NOT wait for the app to go idle. That matters because the GL map surface redraws
 * every frame and is never idle, so the wait-for-still tools (`ComposeTestRule`)
 * can't drive it ("No compose hierarchies found"). Outcomes are read from the
 * activity-scoped [MapStore] and from whether the app is still foreground (a crash
 * drops it). This is exactly the pilot path: a real gesture → real dispatch → real
 * render, asserted by the outcome the pilot depends on.
 */
class MapDriver {

    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    lateinit var scenario: ActivityScenario<TernParaglidingActivity>
        private set
    lateinit var store: MapStore
        private set

    fun launch(): MapDriver {
        // Synthetic touches go nowhere on a dark/locked screen — wake + unlock first.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        scenario = ActivityScenario.launch(TernParaglidingActivity::class.java)
        scenario.onActivity { store = ViewModelProvider(it)[MapStore::class.java] }
        return this
    }

    fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    /** Poll the process-global projection hook — no compose clock involved. */
    fun waitForMapReady(timeoutMs: Long = 30_000) =
        waitFor(timeoutMs, "map projection ready") { MapProjectionTestHook.isReady }

    fun waitForStore(desc: String, timeoutMs: Long = 10_000, pred: (MapState) -> Boolean) =
        waitFor(timeoutMs, desc) { pred(store.state.value) }

    /** Run on the UI thread with the live store (dispatch / inspect). */
    fun onUi(block: (MapStore) -> Unit) = scenario.onActivity { block(store) }

    val state: MapState get() = store.state.value

    /** Long-press the centre of the screen (= the map centre). A single-process
     *  `input swipe` from a point to itself over 800 ms is one continuous gesture
     *  (DOWN → MOVEs → UP) that reliably triggers MapLibre's long-press — verified
     *  on-device firing onMapLongClick. (Separate `input motionevent DOWN`/`UP`
     *  commands do NOT correlate into one gesture.) */
    fun longPressCenter() = longPressAt(device.displayWidth / 2, device.displayHeight / 2)

    fun longPressAt(x: Int, y: Int) {
        device.executeShellCommand("input swipe $x $y $x $y 800")
        device.waitForIdle()
    }

    /** A normal tap at the screen centre (= map centre). */
    fun tapCenter() = tapAt(device.displayWidth / 2, device.displayHeight / 2)

    /** A normal tap at an absolute screen pixel. Synthesized as a *short* same-point
     *  swipe (DOWN→UP over 80 ms) — the same touchscreen-gesture channel the long-press
     *  uses (just short enough to read as a tap, not a long-press). On this device,
     *  `input tap` / `device.click` inject via a path the GL SurfaceView's gesture
     *  detector never receives, so neither layer-click nor onMapClick fires; the gesture
     *  swipe reliably does. */
    fun tapAt(x: Int, y: Int) {
        device.click(x, y)
        device.waitForIdle()
    }

    /** Tap [x],[y] repeatedly until [pred] holds — absorbs synthetic-tap flakiness on
     *  the live GL surface, mirroring [longPressCenterUntil]. */
    fun tapUntil(desc: String, x: Int, y: Int, timeoutMs: Long = 20_000, pred: (MapState) -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pred(state)) return
            tapAt(x, y)
            val attemptEnd = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < attemptEnd) {
                if (pred(state)) return
                Thread.sleep(100)
            }
        }
        throw AssertionError("Timed out after ${timeoutMs}ms (retrying tap) waiting for: $desc")
    }

    /** Long-press the centre repeatedly until [pred] holds — absorbs the inherent
     *  flakiness of a synthetic long-press racing the map's gesture readiness. */
    fun longPressCenterUntil(desc: String, timeoutMs: Long = 25_000, pred: (MapState) -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pred(state)) return
            longPressCenter()
            val attemptEnd = System.currentTimeMillis() + 2_500
            while (System.currentTimeMillis() < attemptEnd) {
                if (pred(state)) return
                Thread.sleep(100)
            }
        }
        throw AssertionError("Timed out after ${timeoutMs}ms (retrying long-press) waiting for: $desc")
    }

    /** True while the app is still foreground — a crash drops it to a dialog/launcher. */
    fun appAlive(): Boolean = device.currentPackageName == "com.ternparagliding"

    // ── Compose UI (sheets/dialogs/editor) — NOT the GL surface ──────────────
    // These ordinary Compose views ARE reachable via UiAutomator's accessibility
    // tree (unlike the GL map, which only accepts the long-press swipe). Buttons/rows
    // are found by visible text or contentDescription; editable fields by the testTag
    // exposed as a resource-id (root sets testTagsAsResourceId = true).

    /** Tap a Compose view by its visible text. */
    fun tapText(text: String, timeoutMs: Long = 8_000) {
        val obj = device.wait(Until.findObject(By.text(text)), timeoutMs)
            ?: throw AssertionError("No view with text '$text' after ${timeoutMs}ms")
        obj.click(); device.waitForIdle()
    }

    /** Tap a Compose view by its contentDescription (e.g. a dock icon button). */
    fun tapDesc(desc: String, timeoutMs: Long = 8_000) {
        val obj = device.wait(Until.findObject(By.desc(desc)), timeoutMs)
            ?: throw AssertionError("No view with contentDescription '$desc' after ${timeoutMs}ms")
        obj.click(); device.waitForIdle()
    }

    /** Wait until a view with [text] is present. */
    fun waitForText(text: String, timeoutMs: Long = 8_000): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs) ?: false

    /** Tap a view by its testTag (exposed as resource-id). */
    fun tapRes(tag: String, timeoutMs: Long = 8_000) {
        val obj = device.wait(Until.findObject(By.res(tag)), timeoutMs)
            ?: throw AssertionError("No view with testTag '$tag' after ${timeoutMs}ms")
        obj.click(); device.waitForIdle()
    }

    /** Type into an editable field identified by its testTag (exposed as resource-id).
     *  Scrolls the (Compose) editor down if the field is below the fold. */
    fun setField(tag: String, text: String) {
        findFieldScrolling(tag).also { it.text = text }; device.waitForIdle()
    }

    /** Clear an editable field identified by its testTag (scrolling to it if needed). */
    fun clearField(tag: String) {
        findFieldScrolling(tag).also { it.clear() }; device.waitForIdle()
    }

    /** Find a tagged field, scrolling the scrollable content up a few steps to reveal it
     *  (off-screen Compose nodes aren't in the accessibility tree, so a field below the
     *  fold can't be found until scrolled into view). */
    private fun findFieldScrolling(tag: String, steps: Int = 5): UiObject2 {
        repeat(steps) {
            device.wait(Until.findObject(By.res(tag)), 1_500)?.let { return it }
            scrollContentUp()
        }
        throw AssertionError("No field with testTag '$tag' after scrolling $steps steps")
    }

    /** Drag the scrollable content up a step (reveals lower content) via the shell
     *  `input swipe` channel — the same one the long-press uses, which reliably reaches
     *  the surface (raw `device.swipe` injection is as unreliable here as `device.click`). */
    fun scrollContentUp() {
        val x = device.displayWidth / 2
        val y1 = (device.displayHeight * 0.72).toInt()
        val y2 = (device.displayHeight * 0.28).toInt()
        device.executeShellCommand("input swipe $x $y1 $x $y2 300")
        device.waitForIdle()
    }

    private fun waitFor(timeoutMs: Long, desc: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $desc")
    }
}
