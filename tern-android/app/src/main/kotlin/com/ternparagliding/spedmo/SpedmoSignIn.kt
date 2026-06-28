package com.ternparagliding.spedmo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * Launches "Sign in with Spedmo" in the **system browser** (Epic 03 3.1).
 *
 * Why the system browser and not an in-app WebView: Spedmo logs in via Google/Facebook, and Google
 * blocks OAuth inside embedded WebViews (`disallowed_useragent`). So we open the real browser at
 * Spedmo's `apiAuthorise.pg`; after the pilot logs in and approves, Spedmo 302-redirects to
 * `tern://spedmo-auth?key=…`, which Android routes back to us (handled in `TernParaglidingActivity`).
 *
 * Only the partner app key travels in the URL — it identifies the Tern app (like an OAuth
 * client_id), not the pilot — so this is safe to carry in a browser navigation.
 */
object SpedmoSignIn {
    private const val TAG = "SpedmoSignIn"

    fun authUrl(): String {
        val key = URLEncoder.encode(SpedmoCredentials.partnerApiKey, "UTF-8")
        return "${SpedmoCredentials.baseUrl}/apiAuthorise.pg?apiKey=$key"
    }

    /** Opens the Spedmo login page. Returns false if no browser could handle the intent. */
    fun launch(context: Context): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl())))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser available to start Spedmo sign-in", e)
            false
        }
    }
}
