package com.ternparagliding.flight.recording

import java.security.MessageDigest

/**
 * Tamper-evidence seam for a sealed [FlightRecording] (Epic 05 5.2 — the black box). The signer
 * runs over the **canonical bytes** of the record (its content, excluding the signature fields
 * themselves) so any later edit is detectable.
 *
 * Two real tiers ship behind this interface:
 *  - [DigestFlightSigner] — a plain SHA-256 content hash. NOT a signature (no key), just an
 *    integrity check; the always-available baseline + the JVM-testable one.
 *  - `KeystoreFlightSigner` (Android only) — signs with a hardware-backed, non-exportable Android
 *    Keystore key + attaches a Key Attestation chain. The on-device tamper-evidence tier.
 *
 * The authoritative tier (server counter-sign + RFC-3161 timestamp on upload) is not a signer —
 * it wraps the file later, server-side, with a key the user never had. See Epic 05 5.2/5.4.
 */
interface FlightSigner {
    val name: String
    fun sign(canonical: ByteArray): Signed?
}

/** A signature (or content digest) over the canonical record bytes, plus optional attestation. */
data class Signed(
    val algorithm: String,
    val signature: String,
    val attestationPem: String? = null,
)

/** No-op: integrity not recorded (e.g. signing disabled). */
object NoopFlightSigner : FlightSigner {
    override val name = "none"
    override fun sign(canonical: ByteArray): Signed? = null
}

/**
 * SHA-256 content hash — an integrity check, not a cryptographic signature (there's no key, so it
 * detects accidental/naive edits but not a determined forger who recomputes it). It's the
 * offline-always baseline and the testable one; the hardware Keystore signer supersedes it on device.
 */
class DigestFlightSigner : FlightSigner {
    override val name = "sha256"
    override fun sign(canonical: ByteArray): Signed {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
        return Signed(algorithm = "SHA-256", signature = digest.toHex())
    }
}

/**
 * Canonical bytes for signing: the record's content with the signature/attestation/timestamp
 * fields blanked (you can't sign over the signature). Stable across runs for the same content.
 */
fun canonicalBytesFor(recording: FlightRecording): ByteArray {
    val unsigned = recording.copy(signature = null, attestationPem = null, serverTimestampToken = null)
    val sb = StringBuilder()
    sb.append(unsigned.id).append('|')
        .append(unsigned.startTimeMs).append('|')
        .append(unsigned.endTimeMs).append('|')
        .append(unsigned.sealReason).append('|')
        .append(unsigned.pilot ?: "").append('|')
        .append(unsigned.gliderType ?: "").append('\n')
    for (f in unsigned.ownTrack) {
        sb.append(f.timeMs).append(',').append(f.lat ?: "").append(',').append(f.lon ?: "")
            .append(',').append(f.gpsAltitudeM ?: "").append(',').append(f.climbMs ?: "")
            .append(',').append(f.pressureHpa ?: "").append('\n')
    }
    for (p in unsigned.peerTrack) {
        sb.append('P').append(p.peerId).append(',').append(p.timeMs).append(',')
            .append(p.lat).append(',').append(p.lon).append('\n')
    }
    for (e in unsigned.events) {
        sb.append('E').append(e.timeMs).append(',').append(e.type).append(',').append(e.detail ?: "").append('\n')
    }
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private fun ByteArray.toHex(): String {
    val hex = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        hex.append("0123456789abcdef"[v ushr 4])
        hex.append("0123456789abcdef"[v and 0x0F])
    }
    return hex.toString()
}
