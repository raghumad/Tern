package com.ternparagliding.overlay.airspace

import com.ternparagliding.overlay.priority.OverlayKind
import com.ternparagliding.overlay.priority.Position
import com.ternparagliding.overlay.priority.distanceDecay
import com.ternparagliding.utils.MapOverlayCacheUtils.OverlayFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class AirspaceGeoJsonTest {

    // ── Helpers ──────────────────────────────────────────────────────

    /** Build an OverlayFeature with a Polygon geometry from raw coords. */
    private fun overlayFeature(
        name: String = "Test TMA",
        airspaceClass: String = "C",
        coords: List<List<Double>> = triangleCoords(),
        geometryType: String = "Polygon",
    ): OverlayFeature {
        val geometry: Map<String, Any> = mapOf(
            "type" to geometryType,
            "coordinates" to if (geometryType == "MultiPolygon") {
                listOf(listOf(coords))
            } else {
                listOf(coords)
            },
        )
        val feature: Map<String, Any> = mapOf(
            "type" to "Feature",
            "geometry" to geometry,
            "properties" to mapOf(
                "name" to name,
                "class" to airspaceClass,
            ),
        )
        val centroid = GeoPoint(
            coords.map { it[1] }.average(),
            coords.map { it[0] }.average(),
        )
        return OverlayFeature(
            internalId = "test-$name",
            feature = feature,
            centroid = centroid,
            hilbertIndex = 42L,
            overlayType = "airspace",
        )
    }

    /** Simple triangle in lon/lat. */
    private fun triangleCoords(): List<List<Double>> = listOf(
        listOf(6.0, 46.0),
        listOf(7.0, 46.0),
        listOf(6.5, 47.0),
        listOf(6.0, 46.0), // closed ring
    )

    private fun candidate(
        name: String = "Test TMA",
        airspaceClass: String = "C",
        coords: List<List<Double>> = triangleCoords(),
        geometryType: String = "Polygon",
    ): AirspaceCandidate {
        val feat = overlayFeature(name, airspaceClass, coords, geometryType)
        return AirspaceCandidate(feature = feat, airspaceClass = airspaceClass)
    }

    // ── GeoJSON conversion ──────────────────────────────────────────

    @Test
    fun `toFeatureCollection produces valid features from candidates`() {
        val candidates = listOf(
            candidate("Alpha TMA", "C"),
            candidate("Bravo Zone", "RESTRICTED"),
        )
        val fc = AirspaceGeoJson.toFeatureCollection(candidates)

        assertEquals(2, fc.features.size)
    }

    @Test
    fun `feature carries class property for layer styling`() {
        val fc = AirspaceGeoJson.toFeatureCollection(
            listOf(candidate("Test", "DANGER"))
        )
        val props = fc.features.first().properties!!
        assertEquals("DANGER", props["class"]?.toString()?.trim('"'))
    }

    @Test
    fun `feature carries name property`() {
        val fc = AirspaceGeoJson.toFeatureCollection(
            listOf(candidate("Geneva TMA", "D"))
        )
        val props = fc.features.first().properties!!
        assertEquals("Geneva TMA", props["name"]?.toString()?.trim('"'))
    }

    @Test
    fun `polygon geometry has correct coordinates`() {
        val feature = AirspaceGeoJson.toFeature(candidate())
        assertNotNull(feature)
        val polygon = feature!!.geometry as org.maplibre.spatialk.geojson.Polygon
        val ring = polygon.coordinates.first()
        assertEquals(4, ring.size) // triangle + closing point
        assertEquals(6.0, ring[0].longitude, 1e-9)
        assertEquals(46.0, ring[0].latitude, 1e-9)
    }

    @Test
    fun `MultiPolygon takes first polygon`() {
        val feature = AirspaceGeoJson.toFeature(
            candidate(geometryType = "MultiPolygon")
        )
        assertNotNull(feature)
    }

    @Test
    fun `degenerate geometry with fewer than 3 vertices returns null`() {
        val twoPoints = listOf(
            listOf(6.0, 46.0),
            listOf(7.0, 46.0),
        )
        val feature = AirspaceGeoJson.toFeature(
            candidate(coords = twoPoints)
        )
        assertNull("degenerate polygon should be dropped", feature)
    }

    @Test
    fun `empty candidate list produces empty FeatureCollection`() {
        val fc = AirspaceGeoJson.toFeatureCollection(emptyList())
        assertTrue(fc.isEmpty())
    }

    // ── Class resolution ────────────────────────────────────────────

    @Test
    fun `resolveAirspaceClass reads string class property`() {
        val feat = overlayFeature(airspaceClass = "D")
        assertEquals("D", AirspaceGeoJson.resolveAirspaceClass(feat))
    }

    @Test
    fun `resolveAirspaceClass normalises CLASS_ prefix`() {
        val feat = overlayFeature()
        // Override the feature map to use CLASS_E
        val modified = OverlayFeature(
            internalId = "test",
            feature = mapOf(
                "type" to "Feature",
                "geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(triangleCoords())),
                "properties" to mapOf("class" to "CLASS_E"),
            ),
            centroid = GeoPoint(46.0, 6.0),
            hilbertIndex = 1L,
            overlayType = "airspace",
        )
        assertEquals("E", AirspaceGeoJson.resolveAirspaceClass(modified))
    }

    @Test
    fun `resolveAirspaceClass falls back to numeric type codes`() {
        val feat = OverlayFeature(
            internalId = "test",
            feature = mapOf(
                "type" to "Feature",
                "geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(triangleCoords())),
                "properties" to mapOf("type" to 1.0),
            ),
            centroid = GeoPoint(46.0, 6.0),
            hilbertIndex = 1L,
            overlayType = "airspace",
        )
        assertEquals("RESTRICTED", AirspaceGeoJson.resolveAirspaceClass(feat))
    }

    @Test
    fun `resolveAirspaceClass maps 0-indexed OpenAIP icaoClass to A-G`() {
        fun byIcao(icao: Double): String = AirspaceGeoJson.resolveAirspaceClass(
            OverlayFeature(
                internalId = "test",
                feature = mapOf(
                    "type" to "Feature",
                    "geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(triangleCoords())),
                    "properties" to mapOf("type" to 0.0, "icaoClass" to icao),
                ),
                centroid = GeoPoint(46.0, 6.0),
                hilbertIndex = 1L,
                overlayType = "airspace",
            )
        )
        // 0-indexed: 0=A .. 6=G. Real "DENVER CLASS B AREA A" carries icaoClass=1.
        assertEquals("A", byIcao(0.0))
        assertEquals("B", byIcao(1.0))
        assertEquals("C", byIcao(2.0))
        assertEquals("D", byIcao(3.0))
        assertEquals("E", byIcao(4.0))
        assertEquals("F", byIcao(5.0))
        assertEquals("G", byIcao(6.0))
        // 7 = unclassified, 8 = SUA: NOT Class G — must stay visible (not dropped).
        assertEquals("UNKNOWN", byIcao(7.0))
        assertEquals("UNKNOWN", byIcao(8.0))
    }

    @Test
    fun `resolveAirspaceClass lets type override icaoClass for special-use airspace`() {
        // Real R-2601 etc. carry type=1 (Restricted) with icaoClass=8 — type wins.
        val feat = OverlayFeature(
            internalId = "test",
            feature = mapOf(
                "type" to "Feature",
                "geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(triangleCoords())),
                "properties" to mapOf("type" to 1.0, "icaoClass" to 8.0),
            ),
            centroid = GeoPoint(46.0, 6.0),
            hilbertIndex = 1L,
            overlayType = "airspace",
        )
        assertEquals("RESTRICTED", AirspaceGeoJson.resolveAirspaceClass(feat))
    }

    @Test
    fun `resolveAirspaceClass returns UNKNOWN for empty properties`() {
        val feat = OverlayFeature(
            internalId = "test",
            feature = mapOf(
                "type" to "Feature",
                "geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(triangleCoords())),
                "properties" to emptyMap<String, Any>(),
            ),
            centroid = GeoPoint(46.0, 6.0),
            hilbertIndex = 1L,
            overlayType = "airspace",
        )
        assertEquals("UNKNOWN", AirspaceGeoJson.resolveAirspaceClass(feat))
    }

    @Test
    fun `isUnrestricted returns true for Class G`() {
        assertTrue(AirspaceGeoJson.isUnrestricted("G"))
        assertTrue(AirspaceGeoJson.isUnrestricted("CLASS_G"))
    }

    @Test
    fun `isUnrestricted returns false for controlled airspace`() {
        listOf("A", "B", "C", "D", "E", "RESTRICTED", "PROHIBITED", "DANGER", "MILITARY", "UNKNOWN")
            .forEach { cls ->
                assertTrue("$cls should not be unrestricted", !AirspaceGeoJson.isUnrestricted(cls))
            }
    }

    // ── AirspaceCandidate scoring ───────────────────────────────────

    @Test
    fun `AirspaceCandidate has kind AIRSPACE`() {
        val c = candidate()
        assertEquals(OverlayKind.AIRSPACE, c.kind)
    }

    @Test
    fun `AirspaceCandidate score uses safety weight 100`() {
        val c = candidate()
        val pilot = Position(46.3, 6.5) // near the triangle
        val score = c.score(pilot)
        val expectedDecay = distanceDecay(c.position.distanceKm(pilot))
        assertEquals(100.0 * expectedDecay, score, 1e-6)
    }

    @Test
    fun `nearby airspace outscores distant PG spot`() {
        val pilot = Position(46.3, 6.5)
        val airspace = candidate()
        val distantSpot = com.ternparagliding.overlay.priority.SimpleOverlayCandidate(
            kind = OverlayKind.PG_SPOT,
            position = Position(48.0, 8.0), // ~200km away
            id = "far-spot",
        )
        assertTrue(
            "nearby airspace (weight 100) beats distant PG spot (weight 20)",
            airspace.score(pilot) > distantSpot.score(pilot),
        )
    }
}
