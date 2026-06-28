package com.ternparagliding.spedmo

import android.content.Context
import com.ternparagliding.BuildConfig

/**
 * Local Spedmo link state (Epic 03/05). Two distinct keys:
 *
 *  - **partner API key** — identifies the *Tern app*, not the pilot. Ships via the gitignored
 *    `spedmo.properties` → [BuildConfig.SPEDMO_API_KEY]; empty on a fresh clone, so the feature is
 *    simply inert until configured.
 *  - **member access key** — the per-pilot bearer token. The pilot pastes/links it in Settings; kept
 *    in its own prefs file (a secret — deliberately NOT in Redux state, which is broadly persisted).
 *
 * Auto-upload defaults **off** (privacy default per Epic 03 3.5): nothing leaves the device until the
 * pilot links an account *and* opts in.
 */
object SpedmoCredentials {
    private const val PREFS = "tern_spedmo"
    private const val KEY_ACCESS = "member_access_key"
    private const val KEY_AUTO_UPLOAD = "auto_upload"
    private const val KEY_LIVE_TRACK = "live_tracking"

    /** The Tern app's partner key. Blank ⇒ the build has no Spedmo config; treat as not-usable. */
    val partnerApiKey: String get() = BuildConfig.SPEDMO_API_KEY

    val baseUrl: String get() = BuildConfig.SPEDMO_BASE_URL

    /** True when the app itself is configured to talk to Spedmo at all (partner key present). */
    val isConfigured: Boolean get() = partnerApiKey.isNotBlank()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The pilot's member access key, or null if not linked. */
    fun accessKey(c: Context): String? = prefs(c).getString(KEY_ACCESS, null)?.trim()?.ifBlank { null }

    /** True when a pilot has linked their Spedmo account on this device. */
    fun isLinked(c: Context): Boolean = accessKey(c) != null

    fun setAccessKey(c: Context, key: String?) =
        prefs(c).edit().putString(KEY_ACCESS, key?.trim()).apply()

    /** Whether finalized flights auto-upload. Default **off** — explicit opt-in. */
    fun autoUpload(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_UPLOAD, false)

    fun setAutoUpload(c: Context, on: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_UPLOAD, on).apply()

    /**
     * Whether to push live position to Spedmo while flying *when cell is available* (Epic 03 3.4).
     * Default **off** — real-time location sharing is more sensitive than a post-flight upload, so
     * it's an explicit, separate opt-in. Always silent offline (the LoRa mesh is the offline view).
     */
    fun liveTracking(c: Context): Boolean = prefs(c).getBoolean(KEY_LIVE_TRACK, false)

    fun setLiveTracking(c: Context, on: Boolean) =
        prefs(c).edit().putBoolean(KEY_LIVE_TRACK, on).apply()

    /** Unlink: forget the pilot's key and reset opt-in. */
    fun clear(c: Context) = prefs(c).edit().clear().apply()
}
