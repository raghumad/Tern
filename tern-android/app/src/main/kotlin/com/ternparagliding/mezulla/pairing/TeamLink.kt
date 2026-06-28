package com.ternparagliding.mezulla.pairing

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Random

/**
 * A Mezulla **team**: a shared LoRa channel name + secret key. Boards configured with the same team
 * (name + psk) hear each other and no one else — the membership boundary behind the buddy roster.
 * "Channel" in Meshtastic terms; "team" to the pilot.
 *
 * [psk] is the shared key (16 random bytes for a freshly [TeamLink.create]d team; empty means an
 * unencrypted/open team). Equality is by value (name + key contents), not array identity.
 */
data class Team(val name: String, val psk: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Team) return false
        return name == other.name && psk.contentEquals(other.psk)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + psk.contentHashCode()
}

/**
 * Encodes/parses the team-share deep link `tern://team?n=<url-encoded name>&k=<hex psk>` — the QR a
 * pilot shows so buddies can join their team, and the link Tern opens to apply it.
 *
 * Same shape and no-Android-dependency rule as [TernPairLink] (plain string parsing, hex key), so
 * it's unit-testable in pure JUnit without Robolectric — and works on minSdk 24 (no java.util.Base64).
 */
object TeamLink {
    private const val PREFIX = "tern://team?"
    private val HEX = Regex("^[0-9a-fA-F]*$")

    /** Length of a freshly generated team key — AES-128. */
    const val PSK_BYTES = 16

    /** A random team key. Pass a seeded [Random] in tests for determinism; SecureRandom in production. */
    fun newPsk(random: Random = SecureRandom()): ByteArray =
        ByteArray(PSK_BYTES).also { random.nextBytes(it) }

    /** A brand-new team with [name] (trimmed) and a fresh random key. */
    fun create(name: String, random: Random = SecureRandom()): Team =
        Team(name.trim(), newPsk(random))

    /** The shareable deep link / QR payload for [team]. */
    fun encode(team: Team): String =
        PREFIX + "n=" + URLEncoder.encode(team.name, "UTF-8") + "&k=" + team.psk.toHex()

    /**
     * Build a [Team] from a name + a hex-encoded key — e.g. a Spedmo club's channel name + PSK
     * (Epic 03 3.9). Returns null if [name] is blank or [pskHex] isn't valid hex; mirrors the
     * validation [parse] applies to the `k=` field.
     */
    fun fromHex(name: String, pskHex: String): Team? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (!HEX.matches(pskHex) || pskHex.length % 2 != 0) return null
        return Team(n, pskHex.hexToBytes())
    }

    /** Parse a `tern://team?...` link back to a [Team], or null if it isn't one / is malformed. */
    fun parse(uriString: String): Team? {
        val s = uriString.trim()
        if (!s.startsWith(PREFIX)) return null
        val query = s.substringAfter(PREFIX, "")
        if (query.isEmpty()) return null

        val params = query.split("&").associate { part ->
            val (k, v) = part.split("=", limit = 2).let {
                if (it.size == 2) it[0] to it[1] else it[0] to ""
            }
            k to v
        }

        val nameEnc = params["n"] ?: return null
        val keyHex = params["k"] ?: return null
        if (nameEnc.isBlank()) return null
        if (!HEX.matches(keyHex) || keyHex.length % 2 != 0) return null

        val name = runCatching { URLDecoder.decode(nameEnc, "UTF-8") }.getOrNull()?.trim() ?: return null
        if (name.isEmpty()) return null

        return Team(name, keyHex.hexToBytes())
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray =
    if (isEmpty()) ByteArray(0) else chunked(2).map { it.toInt(16).toByte() }.toByteArray()
