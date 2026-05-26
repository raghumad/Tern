package com.ternparagliding.sim.swarm

import com.ternparagliding.sim.igc.IgcFlight
import com.ternparagliding.sim.igc.IgcParser

/**
 * Resolves the IGC file referenced by a [ScenarioPilot] off the
 * classpath and parses it into an [IgcFlight].
 *
 * Kept as an `object` (not a class) because there's no per-instance
 * state — a scenario plus a class loader is enough.
 *
 * The classpath was chosen over `java.io.File` because the IGC bundles
 * are checked-in test resources and we want them to follow the JVM /
 * Android resource loader, not the host filesystem. Tests on CI run
 * with a packaged classpath where absolute file paths don't resolve.
 */
object ScenarioLoader {

    /**
     * Load every IGC referenced by [scenario]. Returns a map keyed by
     * [PilotId] so the playback engine can address pilots by handle
     * regardless of list ordering.
     *
     * Throws [ScenarioLoadException] if any referenced resource is
     * absent from the classpath. We fail fast rather than degrading to
     * a partial scenario, because a missing pilot would silently break
     * tests downstream and make the failure hard to trace.
     *
     * The IGC parser may itself throw [com.ternparagliding.sim.igc.IgcParseException]
     * if a file is well-located but malformed; that is propagated.
     */
    fun load(scenario: Scenario, loader: ClassLoader = defaultLoader()): Map<PilotId, IgcFlight> {
        val out = LinkedHashMap<PilotId, IgcFlight>(scenario.pilots.size)
        for (pilot in scenario.pilots) {
            val text = readResource(pilot.igcResourcePath, loader)
                ?: throw ScenarioLoadException(
                    "IGC resource not on classpath for pilot '${pilot.id}': " +
                        pilot.igcResourcePath
                )
            out[pilot.id] = IgcParser.parseString(text)
        }
        return out
    }

    private fun readResource(path: String, loader: ClassLoader): String? {
        // ClassLoader.getResourceAsStream uses path WITHOUT a leading
        // slash; Class.getResourceAsStream uses one. Normalise so
        // callers can write the natural `/igc/flights/...` form.
        val normalised = path.trimStart('/')
        val stream = loader.getResourceAsStream(normalised) ?: return null
        return stream.bufferedReader().use { it.readText() }
    }

    private fun defaultLoader(): ClassLoader =
        Thread.currentThread().contextClassLoader
            ?: ScenarioLoader::class.java.classLoader
}

class ScenarioLoadException(message: String) : RuntimeException(message)
