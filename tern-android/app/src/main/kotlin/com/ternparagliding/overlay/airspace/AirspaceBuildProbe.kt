package com.ternparagliding.overlay.airspace

/**
 * Test seam for [AirspaceGeoJson.toFeatureCollection]. Inert in production:
 * unless an instrumented test installs an [observer], this does nothing.
 *
 * It exists so a test can assert the (potentially expensive, ~hundreds-of-ms
 * for a dense set) GeoJSON build runs **off the main thread** — the contract
 * the off-thread fix establishes. Pure JVM, no Android dependencies, so
 * [AirspaceGeoJson] stays plain-JUnit-testable.
 */
object AirspaceBuildProbe {
    /** Invoked with `Thread.currentThread().name` each time a FeatureCollection is built. */
    @Volatile
    var observer: ((threadName: String) -> Unit)? = null
}
