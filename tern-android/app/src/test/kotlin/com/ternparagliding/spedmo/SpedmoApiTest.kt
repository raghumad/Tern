package com.ternparagliding.spedmo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [SpedmoApi] against a real (mock) HTTP server — proves the wire contract with Spedmo's
 * `ApiV1Controller`: the two auth headers go out, the IGC is the raw body, and responses are
 * classified into Ok / AuthError (don't-retry) / Transient (retry) correctly.
 */
class SpedmoApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SpedmoApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = SpedmoApi(OkHttpClient(), server.url("/").toString(), partnerApiKey = "APP-KEY")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `uploadIgc sends both auth headers and the raw IGC body to the right path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":42}"""))

        val result = api.uploadIgc(accessKey = "PILOT-KEY", igc = "AXTRTERN\nB1100000...")

        assertThat(result).isInstanceOf(SpedmoApi.Result.Ok::class.java)
        val req: RecordedRequest = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/api/v1.0/flightDataUpload.api")
        assertThat(req.getHeader("SPEDMO-API-KEY")).isEqualTo("APP-KEY")
        assertThat(req.getHeader("SPEDMO-ACCESS-KEY")).isEqualTo("PILOT-KEY")
        assertThat(req.body.readUtf8()).startsWith("AXTRTERN")
    }

    @Test
    fun `livetrackUpdate posts the fix as query params with auth headers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = api.livetrackUpdate(
            accessKey = "PILOT-KEY",
            latitude = 46.5, longitude = 6.6, gpsAltitudeM = 2400, pressureAltitudeM = 2380,
        )

        assertThat(result).isInstanceOf(SpedmoApi.Result.Ok::class.java)
        val req: RecordedRequest = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        val url = req.requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/api/v1.0/livetrackUpdate.api")
        assertThat(url.queryParameter("latitude")).isEqualTo("46.5")
        assertThat(url.queryParameter("longitude")).isEqualTo("6.6")
        assertThat(url.queryParameter("gpsAltitude")).isEqualTo("2400")
        assertThat(url.queryParameter("pressureAltitude")).isEqualTo("2380")
        assertThat(req.getHeader("SPEDMO-API-KEY")).isEqualTo("APP-KEY")
        assertThat(req.getHeader("SPEDMO-ACCESS-KEY")).isEqualTo("PILOT-KEY")
    }

    @Test
    fun `getMember validates against member api`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":"a@b.com"}"""))

        val result = api.getMember("PILOT-KEY")

        assertThat(result).isInstanceOf(SpedmoApi.Result.Ok::class.java)
        assertThat(server.takeRequest().path).isEqualTo("/api/v1.0/member.api")
    }

    @Test
    fun `listClubs parses the clubs with their team channel`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """[{"id":7,"name":"Chelan XC","channelName":"Chelan XC","psk":"abcdef0123456789","privateClub":true},
                {"id":8,"name":"Bir","channelName":"Bir","psk":"00ff","privateClub":false}]"""))

        val r = api.listClubs("PILOT-KEY")

        assertThat(r).isInstanceOf(SpedmoApi.ClubsResult.Ok::class.java)
        val clubs = (r as SpedmoApi.ClubsResult.Ok).clubs
        assertThat(clubs).hasSize(2)
        assertThat(clubs[0].id).isEqualTo(7)
        assertThat(clubs[0].channelName).isEqualTo("Chelan XC")
        assertThat(clubs[0].psk).isEqualTo("abcdef0123456789")
        assertThat(clubs[0].privateClub).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/api/v1.0/clubs.api")
    }

    @Test
    fun `listClubs maps a 401 to AuthError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(api.listClubs("BAD")).isInstanceOf(SpedmoApi.ClubsResult.AuthError::class.java)
    }

    @Test
    fun `a 4xx is an AuthError (do not retry)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val result = api.uploadIgc("BAD", "igc")
        assertThat(result).isInstanceOf(SpedmoApi.Result.AuthError::class.java)
        assertThat((result as SpedmoApi.Result.AuthError).code).isEqualTo(401)
    }

    @Test
    fun `a 5xx is Transient (retry)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = api.uploadIgc("PILOT-KEY", "igc")
        assertThat(result).isInstanceOf(SpedmoApi.Result.Transient::class.java)
    }

    @Test
    fun `a dropped connection is Transient (retry)`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val result = api.uploadIgc("PILOT-KEY", "igc")
        assertThat(result).isInstanceOf(SpedmoApi.Result.Transient::class.java)
    }
}
