package com.ternparagliding.spedmo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SpedmoUploadQueue] state machine — the restart-survivable retry behaviour that lets a flight
 * queued offline go out later without losing it: success → UPLOADED, network/5xx → stays QUEUED and
 * retries, 4xx → FAILED (no infinite loop), and the whole thing reloads from disk.
 */
class SpedmoUploadQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: SpedmoApi

    private fun queue(
        dir: java.io.File = tmp.root,
        key: String? = "PILOT-KEY",
        igc: (String) -> String? = { "AXTR\nB-records" },
        maxAttempts: Int = 3,
    ) = SpedmoUploadQueue(dir, api, accessKey = { key }, igcFor = igc, maxAttempts = maxAttempts)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = SpedmoApi(OkHttpClient(), server.url("/").toString(), partnerApiKey = "APP-KEY")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a successful drain marks the flight UPLOADED`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val q = queue()
        q.enqueue("flight-1")
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.QUEUED)

        val r = q.drain()

        assertThat(r.uploaded).isEqualTo(1)
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.UPLOADED)
    }

    @Test
    fun `a transient failure keeps it QUEUED for retry, then a later drain succeeds`() = runBlocking {
        val q = queue()
        q.enqueue("flight-1")

        server.enqueue(MockResponse().setResponseCode(503)) // offline-ish
        q.drain()
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.QUEUED)

        server.enqueue(MockResponse().setResponseCode(200)) // cell came back
        q.drain()
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.UPLOADED)
    }

    @Test
    fun `a 4xx fails immediately without endless retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        val q = queue()
        q.enqueue("flight-1")
        q.drain()
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.FAILED)
    }

    @Test
    fun `repeated transient failures give up after maxAttempts`() = runBlocking {
        val q = queue(maxAttempts = 3)
        q.enqueue("flight-1")
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(503))
            q.drain()
        }
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.FAILED)
    }

    @Test
    fun `drain is a no-op when unlinked (no access key)`() = runBlocking {
        val q = queue(key = null)
        q.enqueue("flight-1")
        val r = q.drain()
        assertThat(r.uploaded).isEqualTo(0)
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.QUEUED) // preserved for later
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a flight with no IGC payload fails rather than looping`() = runBlocking {
        val q = queue(igc = { null })
        q.enqueue("flight-1")
        q.drain()
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.FAILED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the queue survives a restart (reloads pending entries from disk)`() = runBlocking {
        queue().enqueue("flight-1") // first instance enqueues, then goes away

        server.enqueue(MockResponse().setResponseCode(200))
        val reopened = queue() // fresh instance, same dir
        assertThat(reopened.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.QUEUED)
        reopened.drain()
        assertThat(reopened.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.UPLOADED)
    }

    @Test
    fun `enqueue is idempotent and does not reset an uploaded flight`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val q = queue()
        q.enqueue("flight-1")
        q.drain()
        q.enqueue("flight-1") // a stray re-enqueue must not re-open a done flight
        assertThat(q.status("flight-1")).isEqualTo(SpedmoUploadQueue.State.UPLOADED)
    }
}
