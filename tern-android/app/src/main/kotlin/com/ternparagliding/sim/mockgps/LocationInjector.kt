package com.ternparagliding.sim.mockgps

/**
 * Test seam over Android's [android.location.LocationManager] mock-provider
 * API. The IGC replay driver speaks to this interface; production wires it
 * to [AndroidLocationInjector] (which talks to a real `LocationManager`),
 * unit tests wire it to a fake.
 *
 * Why an interface rather than passing a `LocationManager` directly? The
 * sim package is kept Android-free so its tests run on the JVM without
 * Robolectric. `LocationManager` and `Location` are both `final` Android
 * SDK classes, awkward to construct or mock in plain JUnit. Pushing the
 * Android types behind this thin seam lets the IGC playback logic live as
 * ordinary Kotlin.
 */
interface LocationInjector {
    /**
     * Register a mock GPS provider. Idempotent at the call-site level —
     * implementations should remove any prior registration of the same
     * provider before re-adding it, so [start] is safe to call after a
     * previous run leaked state.
     */
    fun addTestProvider(providerName: String)

    /**
     * Push one synthetic fix into the mock provider.
     *
     * @param providerName must match a provider previously registered via
     *   [addTestProvider]. Implementations should throw if not.
     * @param latitudeDeg WGS-84 decimal degrees, positive north.
     * @param longitudeDeg WGS-84 decimal degrees, positive east.
     * @param altitudeMeters metres above the WGS-84 ellipsoid.
     * @param accuracyMeters horizontal accuracy in metres. Tern's location
     *   pipeline treats this as the fix uncertainty; 5 m matches a healthy
     *   open-sky consumer GPS.
     * @param speedMetersPerSecond ground speed in m/s. Derived from
     *   consecutive IGC fixes by the caller.
     * @param bearingDegrees ground track in degrees true (0..360). Derived
     *   from consecutive IGC fixes by the caller.
     * @param timeMillis Location.time — wall-clock epoch ms. The app reads
     *   this as the freshness indicator, so we feed it wall-clock-now even
     *   though the lat/lon come from a historical IGC fix at virtual time
     *   T. If we fed the IGC fix's own timestamp here the app would treat
     *   every fix as years stale and refuse to use it.
     * @param elapsedRealtimeNanos monotonic clock value Android consumers
     *   (FusedLocationProvider, especially) compare against
     *   `SystemClock.elapsedRealtimeNanos()` to gauge freshness. Must be
     *   "now" on the same monotonic clock as the receiving app.
     */
    fun setTestProviderLocation(
        providerName: String,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        accuracyMeters: Float,
        speedMetersPerSecond: Float,
        bearingDegrees: Float,
        timeMillis: Long,
        elapsedRealtimeNanos: Long,
    )

    /**
     * Unregister the mock provider. After this returns, the provider name
     * is free to be re-added. Implementations should swallow "provider not
     * registered" errors — stopping a stopped injector is harmless.
     */
    fun removeTestProvider(providerName: String)
}
