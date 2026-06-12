package com.ternparagliding.claims

import android.content.Context
import com.ternparagliding.model.Route
import com.ternparagliding.model.Waypoint
import com.ternparagliding.utils.cache.RouteCache
import com.ternparagliding.utils.io.RouteIOManager
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
 * Claim **K6 · Route / task** — the promises about a pilot's planned task,
 * demonstrated by driving the real route persistence + import stack (no
 * screenshots, no emulator). See [docs/claims.md].
 *
 * Routes are user-created/imported and stored locally, so "offline" is inherent;
 * the real risks are geometry fidelity through persistence and graceful handling
 * of a malformed import.
 */
class RouteClaimsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cache: RouteCache

    @Before
    fun setUp() {
        context = mockk<Context>()
        every { context.cacheDir } returns tempFolder.root
        cache = RouteCache(context) // real SpatialDiskCache under context.cacheDir
    }

    @After
    fun tearDown() {
        cache.clearCache()
    }

    /** A 3-turnpoint Bir Billing task with distinct FAI cylinder radii. */
    private fun birTask(id: String): Route = Route(
        id = id,
        name = "Bir Billing Task",
        waypoints = listOf(
            Waypoint(lat = 32.22, lon = 76.69, label = "Takeoff", radius = 400.0),
            Waypoint(lat = 32.30, lon = 76.80, label = "TP1", radius = 2000.0),
            Waypoint(lat = 32.10, lon = 76.95, label = "Goal", radius = 1000.0),
        ),
    )

    /**
     * **CLAIM K6 · Offline.** A route the pilot built or imported is local: it
     * survives an app restart and is retrievable with **no network at all**.
     */
    @Test
    fun `offline - a saved route survives a restart and is retrievable with no network`() {
        cache.cacheRoute(birTask("bir-task"))

        // A fresh instance = a real app restart (no in-memory state).
        val loaded = RouteCache(context).getCachedRoute("bir-task")
        assertNotNull("a saved route must survive a restart", loaded)
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
        cache.cacheRoute(original)

        val loaded = RouteCache(context).getCachedRoute("geo-task")
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
     * returns null, never crashes — and a never-saved route degrades to null.
     */
    @Test
    fun `resilient - a malformed task import is rejected gracefully, never crashes`() {
        assertNull("garbage QR import must return null", RouteIOManager.importRouteFromQrString("}{ not json"))
        assertNull("garbage XCTSK import must return null", RouteIOManager.parseXctskContent("<<< not a task >>>"))
        assertNull("empty content must return null", RouteIOManager.parseXctskContent(""))

        // A route that was never saved must degrade to null, not crash.
        assertNull("a missing route must be null", cache.getCachedRoute("never-saved"))
    }
}
