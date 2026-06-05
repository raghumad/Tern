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

    /**
     * Monotonic count of FeatureCollection builds that have *committed*, and the
     * feature count of the most recent one. Lets a pan/journey test assert the
     * overlay actually produced renderable airspace for the current view — a
     * deterministic, zoom-independent signal of forward progress (the property
     * the conflated-trigger fix guarantees). Instrumented tests run in the app
     * process, so they read these statics directly. Inert in production beyond
     * two volatile writes per build.
     */
    @Volatile
    var buildCount: Int = 0

    @Volatile
    var lastFeatureCount: Int = 0

    fun recordBuild(featureCount: Int) {
        lastFeatureCount = featureCount
        buildCount++
    }
}
