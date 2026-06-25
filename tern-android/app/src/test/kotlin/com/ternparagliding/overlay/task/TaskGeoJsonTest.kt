package com.ternparagliding.overlay.task

import com.ternparagliding.model.LocationType
import com.ternparagliding.model.Task
import com.ternparagliding.model.Waypoint
import com.ternparagliding.overlay.priority.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGeoJsonTest {

    // -- Fixtures ---------------------------------------------------------

    private fun waypoint(
        lat: Double,
        lon: Double,
        type: LocationType = LocationType.TURNPOINT,
        label: String? = null,
        id: String = "wp-${lat.hashCode()}-${lon.hashCode()}",
    ) = Waypoint(id = id, lat = lat, lon = lon, type = type, label = label)

    private val aravisTask = Task(
        id = "task-aravis",
        name = "Aravis XC",
        waypoints = listOf(
            waypoint(45.86, 6.48, LocationType.LAUNCH, "Planfait", id = "wp-launch"),
            waypoint(45.82, 6.44, LocationType.SSS, "SSS Ring", id = "wp-sss"),
            waypoint(45.75, 6.35, LocationType.TURNPOINT, "La Clusaz", id = "wp-tp"),
            waypoint(45.70, 6.30, LocationType.ESS, "ESS Ring", id = "wp-ess"),
            waypoint(45.68, 6.28, LocationType.GOAL, "Goal Field", id = "wp-goal"),
        ),
    )

    private val singleWpTask = Task(
        id = "task-single",
        name = "Single WP",
        waypoints = listOf(
            waypoint(46.0, 7.0, LocationType.LAUNCH, "Solo"),
        ),
    )

    private val emptyTask = Task(id = "task-empty", name = "Empty", waypoints = emptyList())

    private val hiddenTask = aravisTask.copy(id = "task-hidden", isVisible = false)

    // -- taskLines -------------------------------------------------------

    @Test
    fun `taskLines produces one LineString per visible multi-waypoint task`() {
        val fc = TaskGeoJson.taskLines(listOf(aravisTask, singleWpTask, emptyTask))
        assertEquals("only aravisTask has >= 2 waypoints", 1, fc.features.size)

        val lineFeature = fc.features.first()
        val coords = lineFeature.geometry.coordinates
        assertEquals(5, coords.size)
        // First coordinate is lon,lat of the first waypoint.
        assertEquals(6.48, coords[0].longitude, 1e-9)
        assertEquals(45.86, coords[0].latitude, 1e-9)
    }

    @Test
    fun `taskLines skips hidden tasks`() {
        val fc = TaskGeoJson.taskLines(listOf(hiddenTask))
        assertTrue(fc.features.isEmpty())
    }

    @Test
    fun `taskLines empty input produces empty collection`() {
        val fc = TaskGeoJson.taskLines(emptyList())
        assertTrue(fc.features.isEmpty())
    }

    @Test
    fun `taskLines feature carries taskId property`() {
        val fc = TaskGeoJson.taskLines(listOf(aravisTask))
        val props = fc.features.first().properties
        assertEquals("task-aravis", props["taskId"]?.toString()?.trim('"'))
    }

    // -- waypointPoints ---------------------------------------------------

    @Test
    fun `waypointPoints produces one Point per waypoint in visible tasks`() {
        val fc = TaskGeoJson.waypointPoints(listOf(aravisTask))
        assertEquals(5, fc.features.size)
    }

    @Test
    fun `waypointPoints skips hidden tasks`() {
        val fc = TaskGeoJson.waypointPoints(listOf(hiddenTask))
        assertTrue(fc.features.isEmpty())
    }

    @Test
    fun `waypointPoints single-waypoint tasks produce one feature`() {
        val fc = TaskGeoJson.waypointPoints(listOf(singleWpTask))
        assertEquals(1, fc.features.size)
    }

    @Test
    fun `waypointFeature carries name, type, label, waypointId, taskId`() {
        val wp = waypoint(45.86, 6.48, LocationType.LAUNCH, "Planfait", id = "wp-1")
        val feature = TaskGeoJson.waypointFeature(wp, 0, "task-1")

        val props = feature.properties
        assertEquals("\"Planfait\"", props["name"].toString())
        assertEquals("\"LAUNCH\"", props["type"].toString())
        assertEquals("\"Planfait\\nLAUNCH\"", props["label"].toString())
        assertEquals("\"wp-1\"", props["waypointId"].toString())
        assertEquals("\"task-1\"", props["taskId"].toString())
    }

    @Test
    fun `waypointFeature defaults label to WP index when label is null`() {
        val wp = waypoint(45.0, 6.0, LocationType.TURNPOINT, label = null, id = "wp-x")
        val feature = TaskGeoJson.waypointFeature(wp, 2, "r")
        val props = feature.properties
        assertEquals("\"WP 3\"", props["name"].toString())
    }

    @Test
    fun `waypointFeature point coordinates are lon,lat`() {
        val wp = waypoint(45.86, 6.48, LocationType.LAUNCH, "Planfait", id = "wp-1")
        val feature = TaskGeoJson.waypointFeature(wp, 0, "task-1")
        assertEquals(6.48, feature.geometry.longitude, 1e-9)
        assertEquals(45.86, feature.geometry.latitude, 1e-9)
    }

    // -- waypointCandidates -----------------------------------------------

    @Test
    fun `waypointCandidates wraps each waypoint as TASK_WAYPOINT candidate`() {
        val candidates = TaskGeoJson.waypointCandidates(listOf(aravisTask))
        assertEquals(5, candidates.size)
        assertTrue(candidates.all { it.kind == OverlayKind.TASK_WAYPOINT })
    }

    @Test
    fun `waypointCandidates skips hidden tasks`() {
        val candidates = TaskGeoJson.waypointCandidates(listOf(hiddenTask))
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `waypointCandidates position matches waypoint coordinates`() {
        val candidates = TaskGeoJson.waypointCandidates(listOf(singleWpTask))
        val c = candidates.single()
        assertEquals(46.0, c.position.latitudeDeg, 1e-9)
        assertEquals(7.0, c.position.longitudeDeg, 1e-9)
    }

    @Test
    fun `waypointCandidates preserves original waypoint reference`() {
        val candidates = TaskGeoJson.waypointCandidates(listOf(aravisTask))
        assertEquals(aravisTask.waypoints[0], candidates[0].waypoint)
    }

    // -- Multiple tasks --------------------------------------------------

    @Test
    fun `multiple visible tasks produce combined waypoint set`() {
        val task2 = Task(
            id = "task-2",
            name = "Task 2",
            waypoints = listOf(
                waypoint(46.0, 7.0, LocationType.LAUNCH, "Start2", id = "wp-2-1"),
                waypoint(46.1, 7.1, LocationType.GOAL, "End2", id = "wp-2-2"),
            ),
        )
        val fc = TaskGeoJson.waypointPoints(listOf(aravisTask, task2))
        assertEquals(7, fc.features.size) // 5 + 2
    }

    @Test
    fun `multiple tasks produce multiple line features`() {
        val task2 = Task(
            id = "task-2",
            name = "Task 2",
            waypoints = listOf(
                waypoint(46.0, 7.0, LocationType.LAUNCH, "Start2", id = "wp-2-1"),
                waypoint(46.1, 7.1, LocationType.GOAL, "End2", id = "wp-2-2"),
            ),
        )
        val fc = TaskGeoJson.taskLines(listOf(aravisTask, task2))
        assertEquals(2, fc.features.size)
    }
}
