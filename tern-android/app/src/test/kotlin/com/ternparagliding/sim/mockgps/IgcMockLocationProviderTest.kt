package com.ternparagliding.sim.mockgps

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ternparagliding.sim.igc.IgcFix
import com.ternparagliding.sim.igc.IgcFlight
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.Scenario
import com.ternparagliding.sim.swarm.ScenarioPilot
import com.ternparagliding.sim.swarm.SwarmPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [IgcMockLocationProvider].
 *
 * The Android `LocationManager` is mocked out via the [LocationInjector]
 * seam so these tests run as plain JVM JUnit 5 — no Robolectric, no
 * emulator. The synthetic IGC flights are kept tiny (a handful of fixes
 * over a few seconds) so virtual time and expected output stay obvious
 * in the assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IgcMockLocationProviderTest {

    /** Records every call to the [LocationInjector] in order. */
    private class FakeInjector : LocationInjector {
        data class FixCall(
            val providerName: String,
            val latitudeDeg: Double,
            val longitudeDeg: Double,
            val altitudeMeters: Double,
            val accuracyMeters: Float,
            val speedMetersPerSecond: Float,
            val bearingDegrees: Float,
            val timeMillis: Long,
            val elapsedRealtimeNanos: Long,
        )

        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val fixes = mutableListOf<FixCall>()

        override fun addTestProvider(providerName: String) {
            added += providerName
        }

        override fun setTestProviderLocation(
            providerName: String,
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeMeters: Double,
            accuracyMeters: Float,
            speedMetersPerSecond: Float,
            bearingDegrees: Float,
            timeMillis: Long,
            elapsedRealtimeNanos: Long,
        ) {
            fixes += FixCall(
                providerName,
                latitudeDeg,
                longitudeDeg,
                altitudeMeters,
                accuracyMeters,
                speedMetersPerSecond,
                bearingDegrees,
                timeMillis,
                elapsedRealtimeNanos,
            )
        }

        override fun removeTestProvider(providerName: String) {
            removed += providerName
        }
    }

    // -- Fixture helpers -------------------------------------------------

    private val pilot = PilotId("dut")
    private val flightDate = LocalDate.of(2026, 1, 1)
    private val t0 = Instant.parse("2026-01-01T12:00:00Z")

    /**
     * Five fixes one second apart at 45N 6E moving 0.0001 deg north per
     * second. That is about 11.12 m per second of latitude change at this
     * latitude under the haversine sphere approximation, so derived
     * ground speed should be ~11 m/s and bearing should be ~0 deg true
     * (due north).
     *
     * Fix layout (i = 0..4):
     *  - timestamp = t0 + i s
     *  - latitude  = 45.0 + i * 0.0001
     *  - longitude = 6.0
     *  - gpsAlt    = 1000 + i * 10
     */
    private fun fiveSecondNorthboundFlight(): IgcFlight {
        val fixes = (0..4).map { i ->
            IgcFix(
                timestamp = t0.plusSeconds(i.toLong()),
                latitude = 45.0 + i * 0.0001,
                longitude = 6.0,
                pressureAltitude = 0,
                gpsAltitude = 1000 + i * 10,
                fixValid = true,
            )
        }
        return IgcFlight(date = flightDate, fixes = fixes)
    }

    private fun playbackFor(flight: IgcFlight, tick: Duration = Duration.ofSeconds(1)): SwarmPlayback {
        val scenario = Scenario(
            name = "mockgps-test",
            location = "",
            date = flight.date,
            region = "xx",
            pilots = listOf(
                ScenarioPilot(
                    id = pilot,
                    displayName = pilot.value,
                    igcResourcePath = "/unused-for-override",
                ),
            ),
            notes = "",
        )
        return SwarmPlayback(
            scenario = scenario,
            tickInterval = tick,
            flightsOverride = mapOf(pilot to flight),
        )
    }

    /**
     * Run the provider's coroutine on an [UnconfinedTestDispatcher]
     * parented to the test's [backgroundScope]. Same pattern as
     * `SwarmSimulatedConnectionTest`: `UnconfinedTestDispatcher` makes
     * `launch {}` start synchronously up to the first suspension, while
     * `delay()` still respects virtual time so [advanceTimeBy] drives
     * the loop deterministically.
     */
    private fun TestScope.providerScope(): CoroutineScope =
        CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
        )

    private fun newProvider(
        playback: SwarmPlayback = playbackFor(fiveSecondNorthboundFlight()),
        injector: FakeInjector = FakeInjector(),
        speedMultiplier: Int = 1,
        wallClockMillis: Long = 1_700_000_000_000L,
    ): Pair<IgcMockLocationProvider, FakeInjector> {
        val provider = IgcMockLocationProvider(
            playback = playback,
            dutPilotId = pilot,
            speedMultiplier = speedMultiplier,
            injector = injector,
            wallClock = Clock.fixed(Instant.ofEpochMilli(wallClockMillis), ZoneOffset.UTC),
            monotonicNanosSupplier = { 42_000_000_000L },
        )
        return provider to injector
    }

    // -- Construction guards --------------------------------------------

    @Test
    fun `speedMultiplier of zero is rejected`() {
        assertThrows<IllegalArgumentException> {
            IgcMockLocationProvider(
                playback = playbackFor(fiveSecondNorthboundFlight()),
                dutPilotId = pilot,
                speedMultiplier = 0,
                injector = FakeInjector(),
            )
        }
    }

    @Test
    fun `pilot not in playback is rejected`() {
        assertThrows<IllegalArgumentException> {
            IgcMockLocationProvider(
                playback = playbackFor(fiveSecondNorthboundFlight()),
                dutPilotId = PilotId("not-here"),
                speedMultiplier = 1,
                injector = FakeInjector(),
            )
        }
    }

    // -- Lifecycle -------------------------------------------------------

    @Test
    fun `start registers the gps test provider and stop removes it`() = runTest {
        val (provider, injector) = newProvider()
        provider.start(providerScope())
        assertThat(injector.added).containsExactly("gps")
        provider.stop()
        assertThat(injector.removed).containsExactly("gps")
    }

    @Test
    fun `double start throws`() = runTest {
        val (provider, _) = newProvider()
        provider.start(providerScope())
        assertThrows<IllegalStateException> { provider.start(providerScope()) }
        provider.stop()
    }

    @Test
    fun `stop is safe to call without a prior start`() {
        val (provider, injector) = newProvider()
        provider.stop()
        // Still removes provider name defensively so a leaked provider
        // from an earlier run gets cleaned up.
        assertThat(injector.removed).containsExactly("gps")
    }

    // -- Tick behaviour --------------------------------------------------

    @Test
    fun `at 1x five-fix flight produces five injected fixes`() = runTest {
        val (provider, injector) = newProvider(speedMultiplier = 1)
        provider.start(providerScope())
        // UnconfinedTestDispatcher means the loop starts synchronously
        // and emits its first fix (at t0) before suspending on delay().
        // advanceTimeBy past the end of the flight so every subsequent
        // delay() resumes and the loop hits its terminal condition
        // (next > lastFixTime).
        advanceTimeBy(6000)
        provider.stop()

        // Ticks land on t0, t0+1, t0+2, t0+3, t0+4 (== lastFixTime).
        // The next step would be t0+5 which is after lastFixTime, so the
        // loop exits. Five fixes total.
        assertWithMessage("injected fixes").that(injector.fixes).hasSize(5)
        assertThat(provider.injectedFixCount).isEqualTo(5)
    }

    @Test
    fun `injected fix carries IGC lat lon and altitude`() = runTest {
        val (provider, injector) = newProvider()
        provider.start(providerScope())
        // 5 ticks at 1000ms each. advance past the last one so the loop
        // hits its terminal condition and exits cleanly.
        advanceTimeBy(6000)
        provider.stop()

        // Fix #0 is the first emitted tick, at virtual t0. Synthetic
        // flight: lat = 45 + i*0.0001 at fix i; i=0 means lat = 45.0,
        // lon = 6.0, gpsAltitude = 1000.
        val first = injector.fixes.first()
        assertThat(first.providerName).isEqualTo("gps")
        assertThat(first.latitudeDeg).isWithin(1e-9).of(45.0)
        assertThat(first.longitudeDeg).isWithin(1e-9).of(6.0)
        assertThat(first.altitudeMeters).isWithin(1e-6).of(1000.0)
        assertThat(first.accuracyMeters).isEqualTo(5.0f)

        // Last fix (#4) is at virtual t0+4s: lat = 45.0004, alt = 1040.
        val last = injector.fixes.last()
        assertThat(last.latitudeDeg).isWithin(1e-9).of(45.0004)
        assertThat(last.altitudeMeters).isWithin(1e-6).of(1040.0)
    }

    @Test
    fun `Location time is wall-clock now not IGC time`() = runTest {
        val wallClock = 1_700_000_000_000L
        val (provider, injector) = newProvider(wallClockMillis = wallClock)
        provider.start(providerScope())
        // 5 ticks at 1000ms each. advance past the last one so the loop
        // hits its terminal condition and exits cleanly.
        advanceTimeBy(6000)
        provider.stop()

        assertThat(injector.fixes).isNotEmpty()
        // Every fix gets the wall-clock-now stamp, NOT t0's epoch millis.
        for (fix in injector.fixes) {
            assertThat(fix.timeMillis).isEqualTo(wallClock)
            assertThat(fix.elapsedRealtimeNanos).isEqualTo(42_000_000_000L)
        }
        // Sanity: the wall-clock stamp is in 2023, the IGC fixes are in
        // 2026, and the two diverge by years. If we had accidentally
        // stamped the IGC fix time, this assertion would catch it.
        val igcEpochMillis = t0.toEpochMilli()
        assertThat(injector.fixes.first().timeMillis).isNotEqualTo(igcEpochMillis)
    }

    @Test
    fun `bearing for a northbound flight is approximately zero degrees`() = runTest {
        val (provider, injector) = newProvider()
        provider.start(providerScope())
        // 5 ticks at 1000ms each. advance past the last one so the loop
        // hits its terminal condition and exits cleanly.
        advanceTimeBy(6000)
        provider.stop()

        assertThat(injector.fixes).isNotEmpty()
        // Flight moves due north (only latitude changes). Initial bearing
        // should be ~0 deg true on every fix.
        for ((i, fix) in injector.fixes.withIndex()) {
            assertWithMessage("bearing at tick $i should be ~0").that(
                fix.bearingDegrees,
            ).isWithin(0.01f).of(0.0f)
        }
    }

    @Test
    fun `speed for a northbound 0_0001 deg per second flight is ~11 m per s`() = runTest {
        val (provider, injector) = newProvider()
        provider.start(providerScope())
        // 5 ticks at 1000ms each. advance past the last one so the loop
        // hits its terminal condition and exits cleanly.
        advanceTimeBy(6000)
        provider.stop()

        assertThat(injector.fixes).isNotEmpty()
        // 0.0001 deg of latitude is ~11.12 m on the haversine sphere
        // approximation. Over 1 s that is 11.12 m/s. Tolerate 0.5 m/s
        // for sphere-vs-ellipsoid approximation drift.
        for ((i, fix) in injector.fixes.withIndex()) {
            assertWithMessage("speed at tick $i").that(fix.speedMetersPerSecond)
                .isWithin(0.5f).of(11.12f)
        }
    }

    @Test
    fun `virtual time advances exactly tickInterval per tick`() = runTest {
        val (provider, _) = newProvider()
        // Before any tick, virtual time sits one step behind the first fix.
        assertThat(provider.currentVirtualTime).isEqualTo(t0.minusSeconds(1))

        provider.start(providerScope())
        // 5 ticks at 1000ms each. advance past the last one so the loop
        // hits its terminal condition and exits cleanly.
        advanceTimeBy(6000)
        provider.stop()

        // After the loop, virtual time should be at the last fix.
        assertThat(provider.currentVirtualTime).isEqualTo(t0.plusSeconds(4))
    }

    @Test
    fun `64x speed multiplier completes 5 ticks in roughly 5_64th of a second`() = runTest {
        val (provider, injector) = newProvider(speedMultiplier = 64)
        provider.start(providerScope())
        // 5 ticks at 1000/64 = 15 ms each = ~75 ms of scheduler time.
        // Advance a generous 200 ms and assert all five fixes are out.
        advanceTimeBy(200)
        provider.stop()
        assertThat(injector.fixes).hasSize(5)
    }

    // -- Math sanity (small unit tests on the static helpers) -----------

    @Test
    fun `haversine 1 degree of latitude is ~111 km`() {
        val d = IgcMockLocationProvider.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertThat(d).isWithin(100.0).of(111_195.0)
    }

    @Test
    fun `initial bearing due east is 90 degrees`() {
        val b = IgcMockLocationProvider.initialBearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertThat(b).isWithin(0.001).of(90.0)
    }

    @Test
    fun `initial bearing due north is 0 degrees`() {
        val b = IgcMockLocationProvider.initialBearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertThat(b).isWithin(0.001).of(0.0)
    }

    @Test
    fun `initial bearing due south is 180 degrees`() {
        val b = IgcMockLocationProvider.initialBearingDegrees(0.0, 0.0, -1.0, 0.0)
        assertThat(b).isWithin(0.001).of(180.0)
    }
}
