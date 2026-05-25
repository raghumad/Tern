package com.madanala.tern.overlay.priority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPrioritizerTest {

    // Pilot is at the launch site in Interlaken, Switzerland.
    private val pilot = Position(46.6863, 7.8632)

    // -- Helpers -------------------------------------------------------

    /** Makes [count] PG spots at increasing distances from [pilot]. */
    private fun pgSpots(count: Int): List<SimpleOverlayCandidate> =
        (1..count).map { i ->
            SimpleOverlayCandidate(
                kind = OverlayKind.PG_SPOT,
                position = Position(
                    pilot.latitudeDeg + i * 0.01,
                    pilot.longitudeDeg,
                ),
                id = "pg-$i",
            )
        }

    /** Makes [count] airspaces nearby (small offset). */
    private fun nearbyAirspaces(count: Int): List<SimpleOverlayCandidate> =
        (1..count).map { i ->
            SimpleOverlayCandidate(
                kind = OverlayKind.AIRSPACE,
                position = Position(
                    pilot.latitudeDeg + i * 0.001,
                    pilot.longitudeDeg,
                ),
                id = "airspace-$i",
            )
        }

    /** Makes [count] peers nearby (small offset). */
    private fun nearbyPeers(count: Int): List<SimpleOverlayCandidate> =
        (1..count).map { i ->
            SimpleOverlayCandidate(
                kind = OverlayKind.PEER,
                position = Position(
                    pilot.latitudeDeg + i * 0.0005,
                    pilot.longitudeDeg,
                ),
                id = "peer-$i",
            )
        }

    // -- Tests ---------------------------------------------------------

    @Test
    fun `safety-critical overlays survive budget cuts over low-weight ones`() {
        val candidates = pgSpots(1000) + nearbyAirspaces(10) + nearbyPeers(3)
        val prioritizer = OverlayPrioritizer(budget = 50)

        val result = prioritizer.prioritize(candidates, pilot)

        assertEquals(50, result.size)

        val peers = result.filter { it.kind == OverlayKind.PEER }
        val airspaces = result.filter { it.kind == OverlayKind.AIRSPACE }
        val spots = result.filter { it.kind == OverlayKind.PG_SPOT }

        assertEquals("all 3 peers survive", 3, peers.size)
        assertEquals("all 10 airspaces survive", 10, airspaces.size)
        assertEquals("remaining budget filled by nearest PG spots", 37, spots.size)
    }

    @Test
    fun `budget halved still preserves high-weight overlays`() {
        val candidates = pgSpots(1000) + nearbyAirspaces(10) + nearbyPeers(3)
        val prioritizer = OverlayPrioritizer(budget = 50)
        prioritizer.budget = prioritizer.budget / 2 // emergency cleanup

        val result = prioritizer.prioritize(candidates, pilot)

        assertEquals(25, result.size)

        val peers = result.filter { it.kind == OverlayKind.PEER }
        val airspaces = result.filter { it.kind == OverlayKind.AIRSPACE }

        assertEquals("all 3 peers survive after halving", 3, peers.size)
        assertEquals("all 10 airspaces survive after halving", 10, airspaces.size)
    }

    @Test
    fun `custom-scored candidate outranks distant airspace`() {
        // A gust front 5 km away with high urgency.
        val gustFront = object : OverlayCandidate {
            override val kind = OverlayKind.WEATHER_MARKER
            override val position = Position(pilot.latitudeDeg + 0.045, pilot.longitudeDeg)
            override fun score(pilotPosition: Position): Double {
                val severity = 50.0
                val minutesUntilArrival = 3.0
                val distKm = position.distanceKm(pilotPosition)
                return severity * (1.0 / (1.0 + minutesUntilArrival)) * distanceDecay(distKm)
            }
        }

        // A far-away airspace (~100 km).
        val distantAirspace = SimpleOverlayCandidate(
            kind = OverlayKind.AIRSPACE,
            position = Position(pilot.latitudeDeg + 1.0, pilot.longitudeDeg),
            id = "far-airspace",
        )

        val result = OverlayPrioritizer(budget = 1)
            .prioritize(listOf(distantAirspace, gustFront), pilot)

        assertEquals(
            "gust front outranks distant airspace",
            gustFront, result.single(),
        )
    }

    @Test
    fun `zero candidates returns empty list`() {
        val result = OverlayPrioritizer().prioritize(emptyList(), pilot)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `budget greater than candidate count returns all`() {
        val candidates = nearbyPeers(5)
        val result = OverlayPrioritizer(budget = 300).prioritize(candidates, pilot)
        assertEquals(5, result.size)
    }

    @Test
    fun `same distance higher safety weight wins`() {
        val samePosition = Position(pilot.latitudeDeg + 0.01, pilot.longitudeDeg)

        val peer = SimpleOverlayCandidate(OverlayKind.PEER, samePosition, "peer")
        val spot = SimpleOverlayCandidate(OverlayKind.PG_SPOT, samePosition, "spot")

        val result = OverlayPrioritizer(budget = 1)
            .prioritize(listOf(spot, peer), pilot)

        assertEquals("higher safety weight wins at same distance", peer, result.single())
    }

    @Test
    fun `distanceDecay produces sane values`() {
        // At 0 km: decay = 1.0
        assertEquals(1.0, distanceDecay(0.0), 1e-9)

        // At 1 km: decay = 0.5
        assertEquals(0.5, distanceDecay(1.0), 1e-9)

        // At 10 km: decay ~ 0.0909
        assertEquals(1.0 / 11.0, distanceDecay(10.0), 1e-9)

        // At 100 km: decay ~ 0.0099
        assertEquals(1.0 / 101.0, distanceDecay(100.0), 1e-9)

        // At 1000 km: decay ~ 0.000999
        assertEquals(1.0 / 1001.0, distanceDecay(1000.0), 1e-9)

        // Monotonically decreasing, never zero.
        val values = listOf(0.0, 1.0, 10.0, 100.0, 1000.0).map { distanceDecay(it) }
        for (i in 0 until values.size - 1) {
            assertTrue(
                "decay at ${listOf(0, 1, 10, 100, 1000)[i]} km > decay at ${listOf(0, 1, 10, 100, 1000)[i + 1]} km",
                values[i] > values[i + 1],
            )
        }
        assertTrue("decay is never zero", values.all { it > 0.0 })
    }

    @Test
    fun `position is renderer-agnostic`() {
        // Position does not reference any map library type. This test
        // just confirms the data class works in isolation.
        val p = Position(47.0, 8.0, 1200.0)
        assertEquals(47.0, p.latitudeDeg, 1e-9)
        assertEquals(8.0, p.longitudeDeg, 1e-9)
        assertEquals(1200.0, p.altitudeMeters, 1e-9)

        // distanceKm to itself is zero.
        assertEquals(0.0, p.distanceKm(p), 1e-9)
    }
}
