package com.ternparagliding.utils.cache

import com.google.common.truth.Truth.assertThat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * The binary "TSI2" spatial-index format (cache v2 step 2): a write→read
 * identity, the cheap header read (data bbox derived from the records, so it
 * can never desync), the magic discriminator vs the old JSON index, and the
 * Hilbert-monotone record/offset ordering a contiguous-survivor read relies on.
 */
class BinaryIndexFormatTest {

    private val bits = 16

    private fun feature(lat: Double, lon: Double): MapOverlayCacheUtils.OverlayFeature {
        val c = GeoPoint(lat, lon)
        return MapOverlayCacheUtils.OverlayFeature(
            feature = mapOf("type" to "Feature", "properties" to mapOf("class" to "D")),
            centroid = c,
            hilbertIndex = MapOverlayCacheUtils.computeHilbertIndex(c, bits),
            overlayType = "airspace",
        )
    }

    @Test
    fun roundTripPreservesEntriesAndOrder() {
        // Build the index the real way so offsets/centroids match the .flex.
        val feats = buildList {
            for (latOff in listOf(-0.4, 0.0, 0.4)) {
                var lon = -106.0
                while (lon <= -76.0) { add(feature(38.9 + latOff, lon)); lon += 1.0 }
            }
        }
        val (index, _) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(feats)

        val bytes = MapOverlayCacheUtils.serializeIndexBinary(index.entries, index.bits, 1_700_000_000_000L)
        assertThat(MapOverlayCacheUtils.isBinaryIndex(bytes)).isTrue()

        val back = MapOverlayCacheUtils.deserializeIndexBinary(bytes)!!
        assertThat(back.bits).isEqualTo(index.bits)
        assertThat(back.entries.size).isEqualTo(index.entries.size)

        // Records ascending by hilbertIndex, and byteOffset strictly increasing in
        // that order (contiguous Hilbert-ordered .flex — the survivor-locality win).
        var prevH = Long.MIN_VALUE
        var prevOff = -1
        back.entries.forEach { e ->
            assertThat(e.hilbertIndex).isAtLeast(prevH)
            assertThat(e.byteOffset).isGreaterThan(prevOff)
            prevH = e.hilbertIndex
            prevOff = e.byteOffset
        }
        // Field-for-field identity vs the source index (already hilbert-sorted).
        val src = index.entries.sortedBy { it.hilbertIndex }
        back.entries.forEachIndexed { i, e ->
            assertThat(e.hilbertIndex).isEqualTo(src[i].hilbertIndex)
            assertThat(e.byteOffset).isEqualTo(src[i].byteOffset)
            assertThat(e.byteLength).isEqualTo(src[i].byteLength)
            assertThat(e.centroidLat).isEqualTo(src[i].centroidLat)
            assertThat(e.centroidLon).isEqualTo(src[i].centroidLon)
        }
    }

    @Test
    fun headerCarriesDataBboxAndCount() {
        val feats = listOf(
            feature(38.0, -106.0), feature(40.0, -77.0), feature(39.0, -90.0),
        )
        val (index, _) = MapOverlayCacheUtils.createSpatialIndexAndSerialize(feats)
        val bytes = MapOverlayCacheUtils.serializeIndexBinary(index.entries, index.bits, 42L)

        val header = MapOverlayCacheUtils.readIndexHeader(bytes)!!
        assertThat(header.count).isEqualTo(3)
        assertThat(header.bits).isEqualTo(bits)
        assertThat(header.writtenAtMs).isEqualTo(42L)
        val b = header.bounds!!
        assertThat(b.minLat).isEqualTo(38.0)
        assertThat(b.maxLat).isEqualTo(40.0)
        assertThat(b.minLon).isEqualTo(-106.0)
        assertThat(b.maxLon).isEqualTo(-77.0)
        // The header bbox must contain every record's centroid (no desync possible).
        MapOverlayCacheUtils.deserializeIndexBinary(bytes)!!.entries.forEach {
            assertThat(it.centroidLat).isIn(com.google.common.collect.Range.closed(b.minLat, b.maxLat))
            assertThat(it.centroidLon).isIn(com.google.common.collect.Range.closed(b.minLon, b.maxLon))
        }
    }

    @Test
    fun emptyIndexHasNullBoundsAndZeroCount() {
        val bytes = MapOverlayCacheUtils.serializeIndexBinary(emptyList(), bits, 1L)
        val header = MapOverlayCacheUtils.readIndexHeader(bytes)!!
        assertThat(header.count).isEqualTo(0)
        assertThat(header.bounds).isNull()
        assertThat(MapOverlayCacheUtils.deserializeIndexBinary(bytes)!!.entries).isEmpty()
    }

    @Test
    fun jsonIndexIsNotMistakenForBinary() {
        // An old JSON .idx must be detected as non-binary so the reader falls back.
        val json = jacksonObjectMapper().writeValueAsBytes(
            MapOverlayCacheUtils.SpatialIndex(listOf(), bits),
        )
        assertThat(MapOverlayCacheUtils.isBinaryIndex(json)).isFalse()
        assertThat(MapOverlayCacheUtils.readIndexHeader(json)).isNull()
        assertThat(MapOverlayCacheUtils.deserializeIndexBinary(json)).isNull()
    }
}
