package com.ternparagliding.sim.mockgps

import com.ternparagliding.sim.igc.IgcFix
import com.ternparagliding.sim.swarm.PilotId
import com.ternparagliding.sim.swarm.SwarmPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drives the DUT pilot's IGC track into the Android location system as
 * if it came from the real GPS receiver. Used by the Aravis IGC replay
 * test so Tern's location-aware features (peer-relative bearings, map
 * centring, GPS-status state machine, etc.) react to the playback the
 * same way they react to a real flight.
 *
 * # How it works
 *
 *  1. [start] registers a mock GPS provider via [LocationInjector] and
 *     launches a coroutine on the caller's scope.
 *  2. The coroutine advances *virtual time* by [SwarmPlayback.tickInterval]
 *     every `tickInterval / speedMultiplier` wall-clock units. At
 *     `speedMultiplier = 1` it is real time. At 64x, a 1-second virtual
 *     tick fires every ~15.6 ms.
 *  3. On each tick the coroutine asks [SwarmPlayback] for the DUT's
 *     position at the current virtual instant, derives ground speed and
 *     track from the bracketing IGC fixes, and feeds the result to the
 *     injector.
 *  4. [stop] cancels the coroutine and removes the mock provider.
 *
 * # Time domain rule
 *
 * The position fed at virtual time T comes from the IGC's recorded fix
 * at T (or linearly interpolated between the two bracketing fixes).
 *
 * The `Location.time` stamped on the synthetic fix is **wall-clock now**,
 * not T. Android consumers — FusedLocationProvider in particular — use
 * `Location.time` and `elapsedRealtimeNanos` as freshness indicators and
 * silently drop fixes more than a few seconds old. If we stamped IGC time
 * (years in the past) Tern would see every fix as stale and the test
 * would observe no location updates.
 *
 * # Mock-location permission
 *
 * Android 6+ requires the app to be selected as the system's mock-location
 * app under Developer Options ("Select mock location app"). Holding
 * `ACCESS_MOCK_LOCATION` is no longer sufficient on its own (it is
 * declared in the manifest only as an opt-in signal). Tests that run this
 * provider on a physical phone must select the Tern test APK in that
 * setting first.
 *
 * # Production safety
 *
 * The class is harmless if never started — its constructor does no I/O,
 * touches no Android APIs, and the production app does not call [start]
 * on it. It lives in `main/` rather than `androidTest/` so the swarm
 * simulator (which lives in `main/` for re-use across test sources) can
 * reference it without a sources-set split.
 *
 * @param playback multi-pilot IGC playback engine.
 * @param dutPilotId which pilot in the playback is the device-under-test
 *   (the phone we are spoofing). Must be a pilot in [playback].
 * @param speedMultiplier how much faster than real time virtual time
 *   advances. 1 = real time. 64 = a 64x replay. Must be > 0.
 * @param injector seam over the Android `LocationManager` mock-provider
 *   API. Production wires [AndroidLocationInjector]; tests inject a fake.
 * @param providerName name of the mock provider to register. Defaults to
 *   `LocationManager.GPS_PROVIDER` ("gps") so consumers that request
 *   updates from the GPS provider by name see the mocked fixes.
 * @param wallClock injected for tests that want deterministic
 *   `Location.time` values. Defaults to [Clock.systemUTC].
 * @param monotonicNanosSupplier injected for tests that want deterministic
 *   `elapsedRealtimeNanos`. Defaults to
 *   [AndroidLocationInjector.nowNanos], i.e. `SystemClock.elapsedRealtimeNanos()`.
 */
class IgcMockLocationProvider(
    private val playback: SwarmPlayback,
    private val dutPilotId: PilotId,
    private val speedMultiplier: Int,
    private val injector: LocationInjector,
    private val providerName: String = DEFAULT_PROVIDER_NAME,
    private val wallClock: Clock = Clock.systemUTC(),
    private val monotonicNanosSupplier: () -> Long = AndroidLocationInjector::nowNanos,
) {

    init {
        require(speedMultiplier > 0) {
            "speedMultiplier must be > 0 (got $speedMultiplier)"
        }
        require(playback.pilots.contains(dutPilotId)) {
            "DUT pilot '$dutPilotId' is not in the playback's scenario"
        }
    }

    private val dutFixes: List<IgcFix> = playback.pilot(dutPilotId).flight.fixes
    private val tickStep: Duration = playback.tickInterval
    private val tickStepMillis: Long = tickStep.toMillis()

    /** Background job; null when not running. */
    @Volatile
    private var job: Job? = null

    /**
     * Highest virtual instant we have already ticked. Initialised one
     * tick before the DUT's first fix so the very first tick lands
     * exactly on `firstFixTime`. Public for tests and debug-time
     * inspection.
     */
    @Volatile
    var currentVirtualTime: Instant = playback.pilot(dutPilotId).firstFixTime.minus(tickStep)
        private set

    /**
     * Counter of fixes injected so far. Useful for tests asserting "we
     * actually pushed N updates" without having to mock-verify call
     * counts.
     */
    @Volatile
    var injectedFixCount: Int = 0
        private set

    /** True between [start] and the coroutine completing or [stop]. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Register the mock provider and launch the playback coroutine on
     * [scope]. Idempotent in the sense that double-start throws — a
     * started provider is single-use. Build a new instance for a new run.
     */
    fun start(scope: CoroutineScope) {
        check(job == null) { "IgcMockLocationProvider.start() called twice" }
        injector.addTestProvider(providerName)
        // Wall-clock delay per tick. Integer division is fine because
        // tickStepMillis is typically a multiple of speedMultiplier in
        // practice (1000 ms / 1..64); even when it is not, the rounding
        // error is one ms per tick which is below any consumer-GPS
        // freshness threshold.
        val realtimePerTickMs = tickStepMillis / speedMultiplier
        job = scope.launch {
            driveLoop(realtimePerTickMs)
        }
    }

    /**
     * Cancel the playback coroutine (if any) and remove the mock
     * provider. Safe to call multiple times and safe to call before
     * [start] — both are no-ops in that case.
     */
    fun stop() {
        job?.cancel()
        job = null
        injector.removeTestProvider(providerName)
    }

    private suspend fun driveLoop(realtimePerTickMs: Long) {
        val end = playback.pilot(dutPilotId).lastFixTime
        while (true) {
            val next = currentVirtualTime.plus(tickStep)
            if (next.isAfter(end)) break
            currentVirtualTime = next
            emitTickAt(next)
            // Pace the loop. delay(0) still yields cooperatively, so
            // cancellation works at any speed multiplier.
            delay(realtimePerTickMs)
        }
    }

    /**
     * Compute the DUT's position at [virtualTime] and push it to the
     * injector. Visible for testing — instrumented tests can drive
     * single ticks without spinning up a coroutine.
     */
    fun emitTickAt(virtualTime: Instant) {
        val position = playback.currentPosition(dutPilotId, virtualTime) ?: return
        val derived = deriveSpeedAndBearing(virtualTime)
        injector.setTestProviderLocation(
            providerName = providerName,
            latitudeDeg = position.latitude,
            longitudeDeg = position.longitude,
            altitudeMeters = position.altitudeMeters.toDouble(),
            accuracyMeters = ACCURACY_METERS,
            speedMetersPerSecond = derived.speedMetersPerSecond,
            bearingDegrees = derived.bearingDegrees,
            timeMillis = wallClock.millis(),
            elapsedRealtimeNanos = monotonicNanosSupplier(),
        )
        injectedFixCount += 1
    }

    /**
     * Ground speed (m/s) and ground track (deg true) at [virtualTime],
     * derived from the IGC fixes bracketing [virtualTime]. The IGC B
     * record itself does not store speed or track — only position +
     * timestamp — so we have to reconstruct them from consecutive fixes.
     *
     * Strategy: find the fix at or just before [virtualTime] (the
     * "floor"), then look forward one fix. Bearing/distance from floor
     * to floor+1 gives a stable estimate at the IGC's native cadence
     * (~1 Hz for most recorders). At the very last fix we look backward
     * instead. With a single-fix flight (degenerate) both speed and
     * bearing default to zero.
     */
    private fun deriveSpeedAndBearing(virtualTime: Instant): DerivedMotion {
        if (dutFixes.size < 2) {
            return DerivedMotion(0f, 0f)
        }
        val idx = findFloorIndex(virtualTime).coerceAtMost(dutFixes.size - 2)
        val a = dutFixes[idx]
        val b = dutFixes[idx + 1]
        val deltaSec = (b.timestamp.toEpochMilli() - a.timestamp.toEpochMilli()) / 1000.0
        if (deltaSec <= 0.0) {
            return DerivedMotion(0f, 0f)
        }
        val distMeters = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val speed = (distMeters / deltaSec).toFloat()
        val bearing = initialBearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude).toFloat()
        return DerivedMotion(speed, bearing)
    }

    /**
     * Largest index `i` such that `dutFixes[i].timestamp <= target`.
     * Falls back to 0 if `target` is before the first fix (shouldn't
     * happen given our tick loop bounds, but defensive).
     */
    private fun findFloorIndex(target: Instant): Int {
        var lo = 0
        var hi = dutFixes.size - 1
        if (target.isBefore(dutFixes[0].timestamp)) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (dutFixes[mid].timestamp.isAfter(target)) {
                hi = mid - 1
            } else {
                lo = mid
            }
        }
        return lo
    }

    private data class DerivedMotion(
        val speedMetersPerSecond: Float,
        val bearingDegrees: Float,
    )

    companion object {
        /**
         * Provider name we register. Matches `LocationManager.GPS_PROVIDER`
         * so consumers that request updates from the GPS provider by name
         * see our mocked fixes. (Hard-coded as a literal so this class
         * stays Android-free at the JVM level for unit testing.)
         */
        const val DEFAULT_PROVIDER_NAME: String = "gps"

        /**
         * Accuracy reported with every fix. 5 m is representative of a
         * healthy open-sky consumer GPS — tight enough that Tern's "good
         * fix" thresholds accept it without question.
         */
        const val ACCURACY_METERS: Float = 5.0f

        private const val EARTH_RADIUS_METERS: Double = 6_371_000.0

        /** Great-circle distance via haversine, in metres. */
        internal fun haversineMeters(
            lat1Deg: Double,
            lon1Deg: Double,
            lat2Deg: Double,
            lon2Deg: Double,
        ): Double {
            val lat1 = Math.toRadians(lat1Deg)
            val lat2 = Math.toRadians(lat2Deg)
            val dLat = Math.toRadians(lat2Deg - lat1Deg)
            val dLon = Math.toRadians(lon2Deg - lon1Deg)
            val a = sin(dLat / 2).let { it * it } +
                cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_METERS * c
        }

        /**
         * Initial great-circle bearing from point 1 to point 2, in
         * degrees true, normalised to `[0, 360)`.
         */
        internal fun initialBearingDegrees(
            lat1Deg: Double,
            lon1Deg: Double,
            lat2Deg: Double,
            lon2Deg: Double,
        ): Double {
            val lat1 = Math.toRadians(lat1Deg)
            val lat2 = Math.toRadians(lat2Deg)
            val dLon = Math.toRadians(lon2Deg - lon1Deg)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            val bearing = Math.toDegrees(atan2(y, x))
            return ((bearing % 360.0) + 360.0) % 360.0
        }

        /** Round speed to one decimal place; useful for logs/tests. */
        internal fun roundedSpeed(speedMps: Float): Float =
            (speedMps * 10).roundToInt() / 10f
    }
}
