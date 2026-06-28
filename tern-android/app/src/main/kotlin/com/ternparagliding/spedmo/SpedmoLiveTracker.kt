package com.ternparagliding.spedmo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.ternparagliding.flight.SensorFix
import com.ternparagliding.flight.export.IgcWriter
import com.ternparagliding.network.HttpClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pushes the pilot's live position to Spedmo while flying, **only when cell is available** (Epic 03
 * 3.4). Fed the same airborne vario fixes the recorder taps; throttled so it costs little battery/
 * data, and completely silent offline — the LoRa buddy mesh remains the offline live view, this is
 * the additive cellular relay for ground crew / out-of-mesh club-mates.
 *
 * Gated three ways (all must hold): the build is configured, the pilot has linked an account, and the
 * pilot opted into live tracking (default off — it's real-time location sharing). Each push is fire-
 * and-forget on a background scope; a failure just means the next throttle tick tries again.
 */
class SpedmoLiveTracker private constructor(private val appContext: Context) {

    private val api = SpedmoApi(
        client = HttpClientProvider.getInstance(appContext),
        baseUrl = SpedmoCredentials.baseUrl,
        partnerApiKey = SpedmoCredentials.partnerApiKey,
    )
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @Volatile
    private var lastSentMs = 0L

    /** Feed one airborne fix. No-ops unless armed, throttled, positioned, and online. */
    fun onAirborneFix(fix: SensorFix) {
        if (!active()) return
        if (!fix.hasPosition) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSentMs < MIN_INTERVAL_MS) return
        if (!hasInternet()) return
        val key = SpedmoCredentials.accessKey(appContext) ?: return

        lastSentMs = nowMs
        val gpsAlt = fix.gpsAltitudeM?.roundToInt() ?: 0
        val pressureAlt = fix.pressureHpa?.let { IgcWriter.pressureAltitudeM(it).roundToInt() } ?: 0
        scope.launch {
            runCatching { api.livetrackUpdate(key, fix.lat!!, fix.lon!!, gpsAlt, pressureAlt) }
                .onFailure { Log.w(TAG, "livetrack push failed", it) }
        }
    }

    /** Reset between flights so the first fix of a new flight goes out immediately. */
    fun reset() { lastSentMs = 0L }

    private fun active(): Boolean =
        SpedmoCredentials.isConfigured &&
            SpedmoCredentials.isLinked(appContext) &&
            SpedmoCredentials.liveTracking(appContext)

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val TAG = "SpedmoLiveTracker"

        /** Min seconds between pushes — enough for ground-crew tracking, light on battery/data. */
        private const val MIN_INTERVAL_MS = 15_000L

        @Volatile
        private var instance: SpedmoLiveTracker? = null

        fun get(context: Context): SpedmoLiveTracker =
            instance ?: synchronized(this) {
                instance ?: SpedmoLiveTracker(context.applicationContext).also { instance = it }
            }
    }
}
