package com.ternparagliding.mezulla.region

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Coverage for the coarse GPS→region table. The point is the *behaviour the
 * pilot sees*: the right band wherever they actually fly, and—just as
 * important—null (leave the board alone) where we have no opinion, so we never
 * stamp a guessed region onto RF hardware.
 */
class LoraRegionTest {

    @Test
    fun `US sites map to US`() {
        // San Francisco, CA and a Rockies launch.
        assertThat(LoraRegion.regionForLocation(37.7749, -122.4194)).isEqualTo(LoraRegion.US)
        assertThat(LoraRegion.regionForLocation(40.7128, -74.0060)).isEqualTo(LoraRegion.US)
        // Anchorage, AK — still US.
        assertThat(LoraRegion.regionForLocation(61.2181, -149.9003)).isEqualTo(LoraRegion.US)
    }

    @Test
    fun `European sites map to EU_868`() {
        // Chamonix, France; Interlaken, Switzerland; London, UK.
        assertThat(LoraRegion.regionForLocation(45.9237, 6.8694)).isEqualTo(LoraRegion.EU_868)
        assertThat(LoraRegion.regionForLocation(46.6863, 7.8632)).isEqualTo(LoraRegion.EU_868)
        assertThat(LoraRegion.regionForLocation(51.5074, -0.1278)).isEqualTo(LoraRegion.EU_868)
    }

    @Test
    fun `the same pilot gets US at home and EU on a trip`() {
        // The travel scenario the workflow exists for.
        val home = LoraRegion.regionForLocation(39.7392, -104.9903) // Denver
        val trip = LoraRegion.regionForLocation(46.5197, 6.6323) // Lausanne
        assertThat(home).isEqualTo(LoraRegion.US)
        assertThat(trip).isEqualTo(LoraRegion.EU_868)
        assertThat(home).isNotEqualTo(trip)
    }

    @Test
    fun `east-Asian sites resolve to their own region, not the broad China box`() {
        assertThat(LoraRegion.regionForLocation(35.6762, 139.6503)).isEqualTo(LoraRegion.JP) // Tokyo
        assertThat(LoraRegion.regionForLocation(37.5665, 126.9780)).isEqualTo(LoraRegion.KR) // Seoul
        assertThat(LoraRegion.regionForLocation(25.0330, 121.5654)).isEqualTo(LoraRegion.TW) // Taipei
        assertThat(LoraRegion.regionForLocation(39.9042, 116.4074)).isEqualTo(LoraRegion.CN) // Beijing
    }

    @Test
    fun `southern-hemisphere sites map correctly`() {
        assertThat(LoraRegion.regionForLocation(-33.8688, 151.2093)).isEqualTo(LoraRegion.ANZ) // Sydney
        assertThat(LoraRegion.regionForLocation(-45.0312, 168.6626)).isEqualTo(LoraRegion.NZ_865) // Queenstown
        assertThat(LoraRegion.regionForLocation(-22.9068, -43.1729)).isEqualTo(LoraRegion.BR_902) // Rio
    }

    @Test
    fun `unmapped locations return null so the board is left untouched`() {
        // Mid-Atlantic, mid-Pacific, central Sahara — we model no region there.
        assertThat(LoraRegion.regionForLocation(0.0, -30.0)).isNull()
        assertThat(LoraRegion.regionForLocation(0.0, -150.0)).isNull()
        assertThat(LoraRegion.regionForLocation(23.0, 15.0)).isNull()
    }

    @Test
    fun `out-of-range and NaN coordinates return null`() {
        assertThat(LoraRegion.regionForLocation(200.0, 0.0)).isNull()
        assertThat(LoraRegion.regionForLocation(0.0, 500.0)).isNull()
        assertThat(LoraRegion.regionForLocation(Double.NaN, Double.NaN)).isNull()
    }
}
