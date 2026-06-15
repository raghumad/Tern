package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.utils.cache.TaskCache
import com.ternparagliding.utils.io.TaskIOManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claim **K6 · Task / task** — the promises about a pilot's planned task,
 * demonstrated by driving the real task persistence + import stack (no
 * screenshots, no emulator). See [docs/claims.md].
 *
 * Tasks are user-created/imported and stored locally, so "offline" is inherent;
 * the real risks are geometry fidelity through persistence and graceful handling
 * of a malformed import.
 */
class TaskClaimsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cache: TaskCache

    @Before
    fun setUp() {
        context = mockk<Context>()
        every { context.cacheDir } returns tempFolder.root
        cache = TaskCache(context) // real SpatialDiskCache under context.cacheDir
    }

    @After
    fun tearDown() {
        cache.clearCache()
    }

    /** A 3-turnpoint Bir Billing task with distinct FAI cylinder radii. */
    private fun birTask(id: String): Task = Task(
        id = id,
        name = "Bir Billing Task",
        waypoints = listOf(
            Waypoint(lat = 32.22, lon = 76.69, label = "Takeoff", radius = 400.0),
            Waypoint(lat = 32.30, lon = 76.80, label = "TP1", radius = 2000.0),
            Waypoint(lat = 32.10, lon = 76.95, label = "Goal", radius = 1000.0),
        ),
    )

    /**
     * **CLAIM K6 · Offline.** A task the pilot built or imported is local: it
     * survives an app restart and is retrievable with **no network at all**.
     */
    @Test
    fun `offline - a saved task survives a restart and is retrievable with no network`() {
        cache.cacheTask(birTask("bir-task"))

        // A fresh instance = a real app restart (no in-memory state).
        val loaded = TaskCache(context).getCachedTask("bir-task")
        assertNotNull("a saved task must survive a restart", loaded)
        assertEquals("Bir Billing Task", loaded!!.name)
    }

    /**
     * **CLAIM K6 · Correct.** The task geometry — every waypoint's position and
     * its FAI cylinder radius — round-trips through persistence exactly. A wrong
     * radius is a wrong task (you'd tag a cylinder you never entered).
     */
    @Test
    fun `correct - task geometry (waypoints + cylinder radii) round-trips exactly`() {
        val original = birTask("geo-task")
        cache.cacheTask(original)

        val loaded = TaskCache(context).getCachedTask("geo-task")
        assertNotNull("the task must be retrievable", loaded)
        assertEquals("waypoint count must be preserved", original.waypoints.size, loaded!!.waypoints.size)

        original.waypoints.zip(loaded.waypoints).forEach { (o, l) ->
            assertEquals("waypoint lat (${o.label})", o.lat, l.lat, 1e-6)
            assertEquals("waypoint lon (${o.label})", o.lon, l.lon, 1e-6)
            assertEquals("FAI cylinder radius (${o.label})", o.radius, l.radius)
        }
    }

    /**
     * **CLAIM K6 · Resilient.** A malformed task import is rejected gracefully —
     * returns null, never crashes — and a never-saved task degrades to null.
     */
    @Test
    fun `resilient - a malformed task import is rejected gracefully, never crashes`() {
        assertNull("garbage QR import must return null", TaskIOManager.importTaskFromQrString("}{ not json"))
        assertNull("garbage XCTSK import must return null", TaskIOManager.parseXctskContent("<<< not a task >>>"))
        assertNull("empty content must return null", TaskIOManager.parseXctskContent(""))

        // A task that was never saved must degrade to null, not crash.
        assertNull("a missing task must be null", cache.getCachedTask("never-saved"))
    }
}
