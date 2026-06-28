package com.ternparagliding.units

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit conversion + formatting. Locks the conversions a pilot relies on (a wrong
 * factor here means a wrong number on the weather screen) and the canonical-fallback
 * behaviour (a missing/unknown preference must format unchanged, never crash).
 */
class UnitsTest {

    @Test
    fun `temperature converts from canonical celsius`() {
        // The exact reported bug: 13 °C with °F preferred must read 55°F, not 13°C.
        assertEquals("55°F", Units.temp(13.0, "°F"))
        assertEquals("13°C", Units.temp(13.0, "°C"))
        assertEquals("286 K", Units.temp(13.0, "K"))
        assertEquals("0°C → 32°F", "32°F", Units.temp(0.0, "°F"))
    }

    @Test
    fun `speed converts from canonical knots`() {
        assertEquals("10 kn", Units.speed(10.0, "kn"))
        assertEquals("12 mph", Units.speed(10.0, "mph"))   // 10 kn ≈ 11.5 mph → 12
        assertEquals("19 km/h", Units.speed(10.0, "kph"))  // 10 kn ≈ 18.5 km/h → 19
        assertEquals("5 m/s", Units.speed(10.0, "m/s"))    // 10 kn ≈ 5.14 m/s → 5
    }

    @Test
    fun `distance and visibility convert from canonical km`() {
        assertEquals("10 km", Units.distance(10.0, "km"))
        assertEquals("6 mi", Units.distance(10.0, "mi"))   // 10 km ≈ 6.21 mi → 6
        assertEquals("50 fur", Units.distance(10.0, "fur"))
    }

    @Test
    fun `altitude and cloudbase convert from canonical metres`() {
        assertEquals("1000 m", Units.altitude(1000.0, "m"))
        assertEquals("3281 ft", Units.altitude(1000.0, "ft")) // 1000 m → 3281 ft
    }

    @Test
    fun `an unknown or empty preference falls back to canonical, never throws`() {
        assertEquals("13°C", Units.temp(13.0, ""))
        assertEquals("13°C", Units.temp(13.0, "garbage"))
        assertEquals("10 kn", Units.speed(10.0, "??"))
        assertEquals("10 km", Units.distance(10.0, ""))
        assertEquals("1000 m", Units.altitude(1000.0, "xyz"))
    }
}
