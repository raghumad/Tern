package com.ternparagliding.mezulla.region

/**
 * Maps a GPS position to the Meshtastic LoRa region the board must use there.
 *
 * Region is a legal/RF-band choice keyed to geography (US 902–928 MHz, EU
 * 868 MHz, …), so the correct region follows the pilot — US in the USA, EU in
 * Europe. [com.ternparagliding.mezulla.MezullaConnectionManager] reconciles
 * the board to this on every connect and whenever the fix crosses into a new
 * region.
 *
 * The table is deliberately COARSE: first-match-wins bounding boxes over the
 * regions paragliding actually happens in. It is NOT a precise border
 * database — within a few km of a coast or national line it can pick the
 * neighbour. That is an accepted trade-off; a full reverse-geocode is heavier
 * and still wrong at borders. When NO box matches (mid-ocean, regions we don't
 * model), [regionForLocation] returns null and the caller leaves the board's
 * region untouched — we never guess a region onto RF hardware.
 *
 * Region code integers match `meshtastic_Config_LoRaConfig_RegionCode` in the
 * firmware's `config.pb.h`. Keep them in sync if that enum changes.
 */
object LoraRegion {

    const val UNSET = 0
    const val US = 1
    const val EU_433 = 2
    const val EU_868 = 3
    const val CN = 4
    const val JP = 5
    const val ANZ = 6
    const val KR = 7
    const val TW = 8
    const val RU = 9
    const val IN = 10
    const val NZ_865 = 11
    const val TH = 12
    const val BR_902 = 26

    private data class Box(
        val code: Int,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    ) {
        fun contains(lat: Double, lon: Double): Boolean =
            lat in minLat..maxLat && lon in minLon..maxLon
    }

    // First-match-wins. More specific / easterly boxes precede the broad ones
    // that would otherwise swallow them (JP/KR/TW/IN/TH before the wide CN box).
    private val BOXES = listOf(
        // Europe (incl. UK, Iberia, Scandinavia, eastern Europe to ~40°E) → EU 868.
        Box(EU_868, 34.0, 72.0, -11.0, 40.0),
        // North America (USA incl. Alaska, Canada, Mexico, Central America) → US.
        Box(US, 7.0, 72.0, -169.0, -52.0),
        // Japan.
        Box(JP, 24.0, 46.0, 129.0, 146.0),
        // South Korea.
        Box(KR, 33.0, 39.0, 124.0, 131.0),
        // Taiwan.
        Box(TW, 21.0, 26.0, 119.0, 122.5),
        // India.
        Box(IN, 6.0, 36.0, 68.0, 97.0),
        // Thailand / nearby mainland SE Asia.
        Box(TH, 5.0, 21.0, 97.0, 106.0),
        // China (broad — must follow the specific east-Asian boxes above).
        Box(CN, 18.0, 54.0, 73.0, 135.0),
        // Australia → ANZ.
        Box(ANZ, -44.0, -10.0, 112.0, 154.0),
        // New Zealand.
        Box(NZ_865, -48.0, -33.0, 166.0, 179.0),
        // Brazil / much of South America.
        Box(BR_902, -34.0, 6.0, -74.0, -34.0),
    )

    /**
     * The Meshtastic region code for [latDeg]/[lonDeg], or null when the
     * location falls outside every modelled region (caller leaves the board's
     * region as-is). Out-of-range or NaN coordinates return null.
     */
    fun regionForLocation(latDeg: Double, lonDeg: Double): Int? {
        // NaN fails every comparison, so it is rejected by these range checks.
        if (latDeg !in -90.0..90.0 || lonDeg !in -180.0..180.0) return null
        return BOXES.firstOrNull { it.contains(latDeg, lonDeg) }?.code
    }

    /** Human-readable region name, for logs. */
    fun name(code: Int): String = when (code) {
        UNSET -> "UNSET"
        US -> "US"
        EU_433 -> "EU_433"
        EU_868 -> "EU_868"
        CN -> "CN"
        JP -> "JP"
        ANZ -> "ANZ"
        KR -> "KR"
        TW -> "TW"
        RU -> "RU"
        IN -> "IN"
        NZ_865 -> "NZ_865"
        TH -> "TH"
        BR_902 -> "BR_902"
        else -> "REGION_$code"
    }
}
