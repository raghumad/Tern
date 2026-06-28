package com.ternparagliding.spedmo

import java.net.URLDecoder

/**
 * Parses the `tern://spedmo-auth` deep link that Spedmo's `apiAuthorise.pg` redirects to after the
 * pilot signs in (Google/Facebook) in the system browser — the return leg of "Sign in with Spedmo".
 *
 *  - `tern://spedmo-auth?key=<accessKey>` → [Success] (the per-pilot member access key)
 *  - `tern://spedmo-auth?cancel=true`     → [Cancelled] (pilot backed out on Spedmo's consent page)
 *
 * Pure string parsing (no Android dependency) so it's covered by plain JUnit. The matching only
 * fires on the exact `tern://spedmo-auth` host, so it never collides with the pairing (`tern://p`)
 * or team-join (`tern://team`) links.
 */
sealed class SpedmoAuthResult {
    data class Success(val accessKey: String) : SpedmoAuthResult()
    object Cancelled : SpedmoAuthResult()
}

object SpedmoAuthLink {
    private const val PREFIX = "tern://spedmo-auth"

    fun parse(uri: String): SpedmoAuthResult? {
        if (uri != PREFIX && !uri.startsWith("$PREFIX?")) return null

        val query = uri.substringAfter('?', "")
        val params = if (query.isEmpty()) {
            emptyMap()
        } else {
            query.split("&").associate { part ->
                val i = part.indexOf('=')
                if (i < 0) decode(part) to "" else decode(part.substring(0, i)) to decode(part.substring(i + 1))
            }
        }

        if (params["cancel"] == "true") return SpedmoAuthResult.Cancelled
        val key = params["key"]?.trim()
        return if (!key.isNullOrBlank()) SpedmoAuthResult.Success(key) else null
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}
