package com.ternparagliding.spedmo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.ternparagliding.flight.export.IgcExporter
import com.ternparagliding.flight.recording.FlightStore
import com.ternparagliding.network.HttpClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android facade over [SpedmoUploadQueue] (Epic 03 3.5 / Epic 05 5.4): builds the [SpedmoApi] from
 * the app's shared OkHttp client + [SpedmoCredentials], points the queue at `filesDir/spedmo_queue`,
 * and resolves each flight's IGC lazily from the recordings store. Drains on three triggers — a
 * sealed flight, network coming back, and a manual logbook action — so an upload queued offline goes
 * out on its own when cell returns, surviving restarts.
 *
 * Nothing leaves the device unless the app is configured (partner key present), the pilot has linked
 * an account, and — for the automatic path — opted in. Manual upload from the logbook bypasses only
 * the opt-in (the pilot asked for it explicitly), never the link.
 */
class SpedmoUploader private constructor(
    private val appContext: Context,
    watchConnectivity: Boolean,
) {
    private val flightStore = FlightStore(File(appContext.filesDir, "recordings"))

    private val api = SpedmoApi(
        client = HttpClientProvider.getInstance(appContext),
        baseUrl = SpedmoCredentials.baseUrl,
        partnerApiKey = SpedmoCredentials.partnerApiKey,
    )

    private val queue = SpedmoUploadQueue(
        dir = File(appContext.filesDir, "spedmo_queue"),
        api = api,
        accessKey = { SpedmoCredentials.accessKey(appContext) },
        igcFor = { id -> flightStore.load(id)?.let { IgcExporter.toIgc(it) } },
    )

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    init {
        if (watchConnectivity && SpedmoCredentials.isConfigured) registerConnectivityDrain()
    }

    /** Per-flight upload state for the logbook (null = local-only / never queued). */
    fun status(flightId: String): SpedmoUploadQueue.State? = queue.status(flightId)

    /** True when finalized flights should auto-queue (configured + linked + opted in). */
    fun autoUploadActive(): Boolean =
        SpedmoCredentials.isConfigured && SpedmoCredentials.isLinked(appContext) &&
            SpedmoCredentials.autoUpload(appContext)

    /** Called when a flight is sealed. Queues + drains only when the automatic path is fully armed. */
    fun onFlightSealed(flightId: String) {
        if (!autoUploadActive()) return
        enqueueAndDrain(flightId)
    }

    /** Explicit logbook "Upload to Spedmo" — needs a linked account, but not the auto opt-in. */
    fun uploadNow(flightId: String) {
        if (!SpedmoCredentials.isConfigured || !SpedmoCredentials.isLinked(appContext)) return
        enqueueAndDrain(flightId)
    }

    /** Drop a flight's queue entry (call when the flight is deleted from the logbook). */
    fun forget(flightId: String) = queue.remove(flightId)

    private fun enqueueAndDrain(flightId: String) {
        queue.enqueue(flightId)
        drain()
    }

    private fun drain() {
        scope.launch {
            runCatching { queue.drain() }
                .onSuccess { Log.i(TAG, "drain: $it") }
                .onFailure { Log.w(TAG, "drain failed", it) }
        }
    }

    private fun registerConnectivityDrain() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = drain() // a network appeared → flush backlog
            })
        }.onFailure { Log.w(TAG, "connectivity callback not registered", it) }
    }

    companion object {
        private const val TAG = "SpedmoUploader"

        @Volatile
        private var instance: SpedmoUploader? = null

        /**
         * Process-wide uploader. The first caller (the recording middleware) sets up the connectivity
         * drain; later callers (the logbook) share it for status + manual upload.
         */
        fun get(context: Context): SpedmoUploader =
            instance ?: synchronized(this) {
                instance ?: SpedmoUploader(context.applicationContext, watchConnectivity = true).also { instance = it }
            }
    }
}
