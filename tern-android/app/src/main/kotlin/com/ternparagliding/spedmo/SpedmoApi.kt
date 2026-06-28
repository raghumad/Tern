package com.ternparagliding.spedmo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Thin client for the Spedmo partner API (`ApiV1Controller`, `/api/v1.0`). Auth is two headers — the
 * partner API key (app identity) + the member access key (the pilot) — matching Spedmo's `APITools`.
 *
 * Outcomes are classified into three buckets so the upload queue knows whether to retry:
 *  - [Result.Ok] — success.
 *  - [Result.AuthError] — bad/missing key or rejected payload (4xx); **don't** retry blindly.
 *  - [Result.Transient] — network failure or 5xx; safe to retry with backoff.
 */
class SpedmoApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val partnerApiKey: String,
) {
    sealed interface Result {
        data class Ok(val body: String) : Result
        data class AuthError(val code: Int, val body: String = "") : Result
        data class Transient(val code: Int?, val cause: Throwable? = null) : Result
    }

    /** Validate a member access key — `GET /member.api`. [Result.Ok] iff the key resolves a member. */
    suspend fun getMember(accessKey: String): Result = exec(accessKey) {
        Request.Builder().url(url("member.api")).get()
    }

    /** Upload an IGC flight — `POST /flightDataUpload.api` (raw IGC as the request body). */
    suspend fun uploadIgc(accessKey: String, igc: String): Result = exec(accessKey) {
        Request.Builder()
            .url(url("flightDataUpload.api"))
            .post(igc.toRequestBody(JSON))
    }

    /**
     * Push one live-track point — `POST /livetrackUpdate.api`. Spedmo reads the fields as request
     * params (server-timestamped), so they go in the query string with an empty JSON body. Used in
     * flight when cell is available; the server appends to the member's current livetrack.
     */
    suspend fun livetrackUpdate(
        accessKey: String,
        latitude: Double,
        longitude: Double,
        gpsAltitudeM: Int,
        pressureAltitudeM: Int,
    ): Result = exec(accessKey) {
        val u = url("livetrackUpdate.api").toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("gpsAltitude", gpsAltitudeM.toString())
            .addQueryParameter("pressureAltitude", pressureAltitudeM.toString())
            .build()
        Request.Builder().url(u).post("{}".toRequestBody(JSON))
    }

    private suspend fun exec(accessKey: String, build: () -> Request.Builder): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = build()
                    .addHeader(HDR_API_KEY, partnerApiKey)
                    .addHeader(HDR_ACCESS_KEY, accessKey)
                    .build()
                client.newCall(req).execute().use(::classify)
            }.getOrElse { Result.Transient(code = null, cause = it) }
        }

    private fun classify(resp: Response): Result {
        val body = resp.body?.string().orEmpty()
        return when {
            resp.isSuccessful -> Result.Ok(body)
            resp.code in 400..499 -> Result.AuthError(resp.code, body)
            else -> Result.Transient(resp.code)
        }
    }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}/api/v1.0/$path"

    companion object {
        // Header names must match Spedmo's ApiAuthorisationController constants.
        const val HDR_API_KEY = "SPEDMO-API-KEY"
        const val HDR_ACCESS_KEY = "SPEDMO-ACCESS-KEY"
        private val JSON = "application/json".toMediaType()
    }
}
