package com.ternparagliding.utils.io

import com.ternparagliding.model.LibraryWaypoint

/**
 * Parses the **waypoint files** comp organisers issue (airtribune / PWC downloads)
 * into standalone [LibraryWaypoint]s for the library. Supports the three formats
 * the pilot picked: **SeeYou .cup**, **OziExplorer / CompeGPS .wpt**, and **.gpx**.
 *
 * Pure (string in, list out) so it's unit-testable without Android. Format is
 * detected from the filename extension, falling back to content sniffing.
 */
object WaypointFileParser {

    /** Parse [content] (optionally hinted by [fileName]) into library waypoints. */
    fun parse(fileName: String?, content: String): List<LibraryWaypoint> {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return when (ext) {
            "cup" -> parseCup(content)
            "gpx" -> parseGpx(content)
            "wpt" -> parseWpt(content)
            else -> sniff(content)
        }
    }

    private fun sniff(content: String): List<LibraryWaypoint> {
        val head = content.take(2000)
        return when {
            head.contains("<gpx", ignoreCase = true) || head.contains("<wpt", ignoreCase = true) -> parseGpx(content)
            head.contains("OziExplorer", ignoreCase = true) -> parseWpt(content)
            head.lineSequence().any { it.trimStart().startsWith("W ") || it.trimStart().startsWith("W\t") } -> parseWpt(content)
            head.contains("name", ignoreCase = true) && head.contains("code", ignoreCase = true) -> parseCup(content)
            else -> parseCup(content) // CSV-ish default
        }
    }

    // ── SeeYou CUP ───────────────────────────────────────────────────────────
    // Header: name,code,country,lat,lon,elev,style,...  Coordinates DDMM.mmmH /
    // DDDMM.mmmH. The waypoint section ends at a "-----Related Tasks-----" marker.
    fun parseCup(content: String): List<LibraryWaypoint> {
        val out = mutableListOf<LibraryWaypoint>()
        for (raw in content.lines()) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("-----")) break // task section follows
            if (line.startsWith("name,", ignoreCase = true)) continue // header
            val parts = splitCsv(line)
            if (parts.size < 5) continue
            val name = parts[0].trim()
            val code = parts[1].trim().ifBlank { name }
            val lat = cupCoord(parts[3]) ?: continue
            val lon = cupCoord(parts[4]) ?: continue
            val alt = parts.getOrNull(5)?.let { parseElevMeters(it) }
            out += libWp(code = code, name = name, lat = lat, lon = lon, alt = alt)
        }
        return dedupe(out)
    }

    /** "3212.600N" → 32.21°, "07625.800E" → 76.43°. DDMM.mmm with hemisphere. */
    private fun cupCoord(s: String): Double? {
        val t = s.trim().replace("\"", "")
        if (t.length < 3) return null
        val hemi = t.last().uppercaseChar()
        if (hemi !in charArrayOf('N', 'S', 'E', 'W')) return null
        val num = t.dropLast(1).toDoubleOrNull() ?: return null
        val deg = (num / 100).toInt()
        val min = num - deg * 100
        val dd = deg + min / 60.0
        return if (hemi == 'S' || hemi == 'W') -dd else dd
    }

    // ── WPT (OziExplorer or CompeGPS) ────────────────────────────────────────
    fun parseWpt(content: String): List<LibraryWaypoint> =
        if (content.lineSequence().any { it.trimStart().startsWith("W ") || it.trimStart().startsWith("W\t") })
            parseCompeGpsWpt(content) else parseOziWpt(content)

    // OziExplorer: 4 header lines, then CSV rows:
    //   number,name,lat(dd),lon(dd),date,symbol,...,description(idx10),...,alt-ft(idx14)
    private fun parseOziWpt(content: String): List<LibraryWaypoint> {
        val out = mutableListOf<LibraryWaypoint>()
        val lines = content.lines()
        for ((i, raw) in lines.withIndex()) {
            if (i < 4) continue // OziExplorer header block
            val line = raw.trim()
            if (line.isBlank()) continue
            val p = splitCsv(line)
            if (p.size < 4) continue
            val name = p[1].trim()
            val lat = p[2].trim().toDoubleOrNull() ?: continue
            val lon = p[3].trim().toDoubleOrNull() ?: continue
            val desc = p.getOrNull(10)?.trim()?.takeIf { it.isNotBlank() }
            val altFt = p.getOrNull(14)?.trim()?.toDoubleOrNull()?.takeIf { it > -700 }
            out += libWp(code = name, name = desc, lat = lat, lon = lon, alt = altFt?.let { it * 0.3048 })
        }
        return dedupe(out)
    }

    // CompeGPS: "W <code> A <lat> <lon> <date> <time> <alt> <desc...>"
    // Coordinates are decimal degrees with a º/° glyph and an N/S/E/W hemisphere.
    private fun parseCompeGpsWpt(content: String): List<LibraryWaypoint> {
        val out = mutableListOf<LibraryWaypoint>()
        for (raw in content.lines()) {
            val line = raw.trim()
            if (!line.startsWith("W ") && !line.startsWith("W\t")) continue
            val tok = line.split(Regex("\\s+"))
            if (tok.size < 5) continue
            val code = tok[1]
            val lat = compeCoord(tok[3]) ?: continue
            val lon = compeCoord(tok[4]) ?: continue
            val alt = tok.getOrNull(7)?.toDoubleOrNull()?.takeIf { it != 0.0 }
            val desc = if (tok.size > 8) tok.drop(8).joinToString(" ").trim().ifBlank { null } else null
            out += libWp(code = code, name = desc, lat = lat, lon = lon, alt = alt)
        }
        return dedupe(out)
    }

    /** "32.2360000ºN" / "076.706000°E" → signed decimal degrees. */
    private fun compeCoord(s: String): Double? {
        val cleaned = s.replace("º", "").replace("°", "").trim()
        if (cleaned.isEmpty()) return null
        val hemi = cleaned.last().uppercaseChar()
        val (numStr, neg) = if (hemi in charArrayOf('N', 'S', 'E', 'W'))
            cleaned.dropLast(1) to (hemi == 'S' || hemi == 'W')
        else cleaned to false
        val v = numStr.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    // ── GPX ──────────────────────────────────────────────────────────────────
    // <wpt lat=".." lon=".."><name>..</name><ele>..</ele><desc>..</desc></wpt>
    fun parseGpx(content: String): List<LibraryWaypoint> {
        val out = mutableListOf<LibraryWaypoint>()
        val wptRegex = Regex("<wpt\\b[^>]*?>.*?</wpt>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val latRegex = Regex("\\blat\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val lonRegex = Regex("\\blon\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        fun tag(block: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        for (m in wptRegex.findAll(content)) {
            val block = m.value
            val lat = latRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = lonRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val code = tag(block, "name") ?: tag(block, "cmt") ?: "WP"
            val desc = tag(block, "desc") ?: tag(block, "cmt")
            val alt = tag(block, "ele")?.toDoubleOrNull()
            out += libWp(code = code, name = desc?.takeIf { it != code }, lat = lat, lon = lon, alt = alt)
        }
        return dedupe(out)
    }

    // ── shared helpers ─────────────────────────────────────────────────────────
    private fun libWp(code: String, name: String?, lat: Double, lon: Double, alt: Double?): LibraryWaypoint {
        val c = code.trim().ifBlank { "WP" }
        return LibraryWaypoint(id = c, code = c, name = name?.trim()?.takeIf { it.isNotBlank() && it != c }, lat = lat, lon = lon, alt = alt)
    }

    /** Keep the last occurrence per code (id), so a re-import refreshes cleanly. */
    private fun dedupe(list: List<LibraryWaypoint>): List<LibraryWaypoint> =
        list.associateBy { it.id }.values.toList()

    private fun parseElevMeters(s: String): Double? {
        val t = s.trim().lowercase()
        val num = t.replace("m", "").replace("ft", "").trim().toDoubleOrNull() ?: return null
        return if (t.endsWith("ft")) num * 0.3048 else num
    }

    /** CSV split that respects double-quoted fields. */
    private fun splitCsv(line: String): List<String> =
        line.split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")).map { it.trim().removeSurrounding("\"") }
}
