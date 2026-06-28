package com.ternparagliding.weather

import com.google.common.truth.Truth.assertThat
import com.ternparagliding.utils.io.ProfileLevel
import com.ternparagliding.utils.io.dewpointC
import org.junit.Test

/**
 * The parcel-method sounding math. Validates cloudbase (LCL), thermal/convective top,
 * inversion detection and graceful nulls — the accuracy Phase 2 adds, checked here in
 * the JVM rather than eyeballed on a device.
 */
class SoundingTest {

    private fun lvl(p: Int, h: Double, t: Double) = ProfileLevel(p, h, t, t - 5.0, 10.0, 270.0)

    @Test
    fun `cloudbase from the surface temperature-dewpoint spread`() {
        val prof = listOf(lvl(925, 750.0, 20.0), lvl(850, 1500.0, 13.0), lvl(700, 3000.0, 2.0))
        val s = analyseSounding(surfaceTempC = 25.0, surfaceDewpointC = 15.0, surfaceHeightM = 300.0, profile = prof)!!
        // spread 10 °C → 10 / (9.8 − 1.8) per km = 1250 m AGL
        assertThat(s.cloudBaseAglM).isWithin(1.0).of(1250.0)
        assertThat(s.cloudBaseMslM).isWithin(1.0).of(1550.0)
    }

    @Test
    fun `thermal top lands between levels for an unstable profile`() {
        val prof = listOf(lvl(925, 750.0, 20.0), lvl(850, 1500.0, 13.0), lvl(700, 3000.0, 2.0))
        val s = analyseSounding(25.0, 15.0, 300.0, prof)!!
        assertThat(s.thermalTopMslM).isNotNull()
        assertThat(s.thermalTopMslM!!).isGreaterThan(1500.0)
        assertThat(s.thermalTopMslM!!).isLessThan(3000.0)
        assertThat(s.thermalTopAglM!!).isGreaterThan(0.0)
        assertThat(s.cumulus).isTrue() // cloudbase 1550 m < top
    }

    @Test
    fun `stable air (warmer aloft) gives no thermal top`() {
        val prof = listOf(lvl(925, 750.0, 24.0), lvl(850, 1500.0, 26.0))
        val s = analyseSounding(10.0, 5.0, 300.0, prof)!!
        assertThat(s.thermalTopAglM!!).isWithin(1.0).of(0.0)
        assertThat(s.cumulus).isFalse()
    }

    @Test
    fun `inversion base is the height where environment temperature starts rising`() {
        val prof = listOf(lvl(925, 750.0, 20.0), lvl(850, 1500.0, 22.0))
        val s = analyseSounding(25.0, 15.0, 300.0, prof)!!
        assertThat(s.inversionBaseMslM).isEqualTo(750.0)
    }

    @Test
    fun `no profile level above the surface returns null`() {
        val prof = listOf(lvl(925, 750.0, 20.0))
        assertThat(analyseSounding(25.0, 15.0, 1000.0, prof)).isNull()
    }

    @Test
    fun `dewpoint never exceeds temperature and tracks humidity`() {
        assertThat(dewpointC(20.0, 100.0)).isWithin(0.3).of(20.0) // saturated → Td ≈ T
        assertThat(dewpointC(20.0, 50.0)).isLessThan(20.0)
        assertThat(dewpointC(30.0, 30.0)).isLessThan(dewpointC(30.0, 60.0)) // drier → lower Td
    }
}
