package com.ternparagliding.weather

import com.ternparagliding.utils.io.ProfileLevel

/**
 * Soaring metrics derived from a vertical profile (a Skew-T sounding) — the things a
 * two-level (850/925 hPa) proxy can't give: a real **cloudbase**, **thermal/convective
 * top** (how high you can climb), **inversion height**, and a layer lapse rate. All
 * pure + transparent; the UI shows the numbers and lets the pilot judge.
 *
 * The model is a pragmatic, documented parcel method (not a full thermodynamic LCL/CCL
 * solver): a parcel rises **dry-adiabatically** (9.8 °C/km) from the surface; cloudbase
 * is the lifting condensation level from the surface temperature–dewpoint spread; the
 * thermal top is where the rising parcel cools to the environment temperature; an
 * inversion is the first layer where environment temperature rises with height.
 */
data class SoundingAnalysis(
    val cloudBaseAglM: Double,        // convective cloudbase above the surface (LCL)
    val cloudBaseMslM: Double,        // ... as MSL
    val thermalTopAglM: Double?,      // top of dry convection above the surface (null if stable/none)
    val thermalTopMslM: Double?,
    val inversionBaseMslM: Double?,   // lowest capping inversion height (MSL), if any
    val surfaceLapseCPerKm: Double?,  // surface → first profile level lapse rate
    val cumulus: Boolean,             // cumulus expected (cloudbase below the thermal top)
)

private const val DRY_ADIABAT_C_PER_M = 0.0098 // 9.8 °C/km
private const val DEW_FALL_C_PER_M = 0.0018     // dewpoint falls ~1.8 °C/km when lifted

/**
 * Analyse a parcel rising from [surfaceTempC]/[surfaceDewpointC] at [surfaceHeightM]
 * against the environmental [profile]. Returns null if no profile level sits above the
 * surface.
 */
fun analyseSounding(
    surfaceTempC: Double,
    surfaceDewpointC: Double,
    surfaceHeightM: Double,
    profile: List<ProfileLevel>,
): SoundingAnalysis? {
    val env = profile.filter { it.heightM > surfaceHeightM }.sortedBy { it.heightM }
    if (env.isEmpty()) return null

    // LCL: a dry parcel cools at 9.8 °C/km while its dewpoint falls ~1.8 °C/km, so they
    // meet (saturation → cloudbase) at spread / (9.8 − 1.8) per km.
    val spread = (surfaceTempC - surfaceDewpointC).coerceAtLeast(0.0)
    val lclAgl = spread / (DRY_ADIABAT_C_PER_M - DEW_FALL_C_PER_M)
    val lclMsl = surfaceHeightM + lclAgl

    // Thermal top: the rising dry parcel starts equal to the surface (diff = 0) and is
    // buoyant where it stays warmer than the environment. Find the first level where the
    // parcel is no longer warmer (equilibrium) and interpolate the crossing height.
    var topMsl: Double? = null
    var prevH = surfaceHeightM
    var prevDiff = 0.0
    for (lvl in env) {
        val parcelT = surfaceTempC - DRY_ADIABAT_C_PER_M * (lvl.heightM - surfaceHeightM)
        val diff = parcelT - lvl.tempC
        if (diff <= 0.0) {
            val denom = prevDiff - diff
            val frac = if (denom != 0.0) prevDiff / denom else 0.0
            topMsl = prevH + frac.coerceIn(0.0, 1.0) * (lvl.heightM - prevH)
            break
        }
        prevH = lvl.heightM
        prevDiff = diff
    }

    // Inversion: the first layer (surface-up) where environment temperature rises with
    // height — the lid that caps the day.
    var inversionMsl: Double? = null
    var lastH = surfaceHeightM
    var lastT = surfaceTempC
    for (lvl in env) {
        if (lvl.tempC > lastT) { inversionMsl = lastH; break }
        lastH = lvl.heightM
        lastT = lvl.tempC
    }

    val first = env.first()
    val surfaceLapse = ((surfaceTempC - first.tempC) / ((first.heightM - surfaceHeightM) / 1000.0))
        .takeIf { it.isFinite() }

    val thermalTopAgl = topMsl?.let { (it - surfaceHeightM).coerceAtLeast(0.0) }
    return SoundingAnalysis(
        cloudBaseAglM = lclAgl,
        cloudBaseMslM = lclMsl,
        thermalTopAglM = thermalTopAgl,
        thermalTopMslM = topMsl,
        inversionBaseMslM = inversionMsl,
        surfaceLapseCPerKm = surfaceLapse,
        cumulus = topMsl != null && lclMsl < topMsl,
    )
}
