package com.ternparagliding.claims

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.task.TaskGeoJson
import org.junit.Test

/**
 * Claim **K12 · Cylinder polygons are always closed** — building a task's FAI
 * cylinders never crashes the map, even for awkward coordinates (e.g. a waypoint
 * at lon/lat 0 from an unbound import placeholder), because the ring is closed
 * exactly. Regression for an IllegalArgumentException ("ring not closed") that
 * crashed the whole composition.
 */
class TaskCylinderClaimsTest {

    @Test
    fun `cylinder ring is closed at awkward coordinates`() {
        val task = Task(
            id = "t", name = "T",
            waypoints = listOf(
                Waypoint(id = "a", lat = 0.0, lon = 0.0, radius = 1000.0),   // equator/prime meridian
                Waypoint(id = "b", lat = 54.77, lon = -2.55, radius = 2000.0), // normal UK
            ),
        )

        // Must not throw (Polygon ctor validates closure) — this is the crash regression.
        val fc = TaskGeoJson.taskCylinders(listOf(task))

        assertThat(fc.features).hasSize(2)
        fc.features.forEach { f ->
            val ring = f.geometry!!.coordinates.first()
            assertThat(ring.first()).isEqualTo(ring.last()) // closed exactly
            assertThat(ring.size).isAtLeast(4)
        }
    }
}
