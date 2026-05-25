package com.madanala.tern.overlay.pgspot

import com.madanala.tern.overlay.priority.OverlayKind
import com.madanala.tern.overlay.priority.OverlayPrioritizer
import com.madanala.tern.overlay.priority.Position
import com.madanala.tern.utils.MapOverlayCacheUtils.OverlayFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class PgSpotGeoJsonTest {

    // -- helpers ----------------------------------------------------------

    private fun spot(
        lat: Double,
        lon: Double,
        name: String,
        siteType: String = "launch",
    ): OverlayFeature = OverlayFeature(
        internalId = "spot_${lat}_${lon}",
        centroid = GeoPoint(lat, lon),
        hilbertIndex = 0L,
        overlayType = "pgspot",
    ).apply {
        _featureMap = mapOf("name" to name, "siteType" to siteType)
    }

    // Use reflection to set _featureMap since it's private.
    // Cleaner than creating FlexBuffer test data.
    private var OverlayFeature._featureMap: Map<String, Any>?
        get() {
            val field = OverlayFeature::class.java.getDeclaredField("_featureMap")
            field.isAccessible = true
            return field.get(this) as? Map<String, Any>
        }
        set(value) {
            val field = OverlayFeature::class.java.getDeclaredField("_featureMap")
            field.isAccessible = true
            field.set(this, value)
        }

    // -- tests ------------------------------------------------------------

    @Test
    fun `overlayFeaturesToGeoJson produces correct GeoJSON points`() {
        val features = listOf(
            spot(45.8, 6.5, "Col de la Forclaz"),
            spot(46.0, 6.8, "Planfait"),
        )

        val fc = overlayFeaturesToGeoJson(features)

        assertEquals(2, fc.features.size)

        val first = fc.features[0]
        val props = first.properties as JsonObject
        assertEquals("Col de la Forclaz", props["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `empty input produces empty feature collection`() {
        val fc = overlayFeaturesToGeoJson(emptyList())
        assertTrue(fc.features.isEmpty())
    }

    @Test
    fun `PgSpotCandidate scores with PG_SPOT kind`() {
        val overlay = spot(45.8, 6.5, "Test Spot")
        val candidate = PgSpotCandidate(overlay)

        assertEquals(OverlayKind.PG_SPOT, candidate.kind)
        assertEquals(45.8, candidate.position.latitudeDeg, 1e-9)
        assertEquals(6.5, candidate.position.longitudeDeg, 1e-9)
    }

    @Test
    fun `PgSpotCandidate integrates with OverlayPrioritizer`() {
        val pilot = Position(45.8, 6.5)
        val candidates = (1..100).map { i ->
            PgSpotCandidate(spot(45.8 + i * 0.01, 6.5, "Spot $i"))
        }

        val prioritizer = OverlayPrioritizer(budget = 10)
        val result = prioritizer.prioritize(candidates, pilot)

        assertEquals(10, result.size)
        // Nearest spots should win (lower index = closer to pilot)
        val firstCandidate = result.first() as PgSpotCandidate
        assertTrue(
            "nearest spots survive budget",
            firstCandidate.position.latitudeDeg < 45.85, // within ~0.05 deg
        )
    }

    @Test
    fun `missing name defaults to empty string`() {
        val overlay = OverlayFeature(
            internalId = "no-name",
            centroid = GeoPoint(46.0, 7.0),
            hilbertIndex = 0L,
            overlayType = "pgspot",
        ).apply { _featureMap = emptyMap() }

        val fc = overlayFeaturesToGeoJson(listOf(overlay))
        val props = fc.features[0].properties as JsonObject
        assertEquals("", props["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `siteType is included when present`() {
        val overlay = spot(46.0, 7.0, "Landing Zone", siteType = "landing")
        val fc = overlayFeaturesToGeoJson(listOf(overlay))
        val props = fc.features[0].properties as JsonObject
        assertEquals("landing", props["siteType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `EMPTY_PG_SPOT_COLLECTION is empty`() {
        assertTrue(EMPTY_PG_SPOT_COLLECTION.features.isEmpty())
    }
}
