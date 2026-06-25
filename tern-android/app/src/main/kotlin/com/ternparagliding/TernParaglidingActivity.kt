@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ternparagliding

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.mezulla.MezullaConnectionManager
import com.ternparagliding.mezulla.pairing.PairingOrchestrator
import com.ternparagliding.mezulla.pairing.TernPairLink
import com.ternparagliding.redux.MapStore
import com.ternparagliding.ui.screens.TernMapScreen
import com.ternparagliding.ui.theme.TernTheme
import com.ternparagliding.utils.cache.CacheManager
import org.osmdroid.config.Configuration

class TernParaglidingActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TernParaglidingActivity"
        private const val PREFS_NAME = "tern_settings_prefs"
    }

    // The orchestrator and connection manager are owned by [TernApplication]
    // at process scope, NOT by the Activity — the persistent BLE link must
    // survive Activity recreation (rotation, config change, process-restore).
    // These accessors keep the existing call sites (incl. on-device test
    // harnesses: composeTestRule.activity.connectionManager) working.
    val pairingOrchestrator: PairingOrchestrator
        get() = (application as TernApplication).pairingOrchestrator

    /**
     * Persistent BLE connection manager (process-scoped, owned by
     * [TernApplication]). Public for on-device test harnesses (Aravis
     * replay) that need to reach the live
     * [com.ternparagliding.mezulla.connection.ble.BleConnection] to pump
     * synthetic Position frames through it. Production UX paths must not
     * touch this directly — they go through Redux peerState.
     */
    val connectionManager: MezullaConnectionManager
        get() = (application as TernApplication).connectionManager

    private var pendingPairLink: TernPairLink? = null

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            pendingPairLink?.let { link ->
                Log.i(TAG, "BLE permissions granted, starting pairing")
                pairingOrchestrator.handlePairLink(link)
            }
        } else {
            Log.w(TAG, "BLE permissions denied")
        }
        pendingPairLink = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        CacheManager.initialize(applicationContext)

        Configuration.getInstance().load(applicationContext, getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        // Get the activity-scoped MapStore ViewModel and wire the persistent
        // BLE connection. The same ViewModel instance is used by TernMapScreen
        // (passed explicitly below) so there is exactly one MapStore per activity.
        // Bind this Activity's MapStore to the process-scoped connection
        // manager. First call (process start) sets up the pairing observer +
        // auto-reconnect; subsequent calls (Activity recreated) re-point the
        // peer-event stream at the new store while keeping the live link.
        // The pairing-flow hooks (onPairStart / isAlreadyLinked) are wired
        // once in TernApplication, not here.
        val mapStore = ViewModelProvider(this)[MapStore::class.java]
        connectionManager.initialize(mapStore)

        intent?.let { handleDeepLink(it) }

        setContent {
            TernTheme(darkTheme = true) {
                Surface(
                    // Expose Compose testTags as resource-ids so UiAutomator (the only
                    // driver that works over the live GL map) can address fields/buttons
                    // by tag in instrumented L1 claim tests. No effect on production UI.
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                    color = com.ternparagliding.ui.theme.AeroNeonCyan
                ) {
                    TernMapScreen(store = mapStore)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data?.toString() ?: return

        // Team join link (tern://team?…): record the team *intent* on the shared store. The reconcile
        // effect (MapViewContainer) writes it to the board when the link is up — so joining works even
        // with the board off, and we don't depend on a live link at the moment the link is opened.
        com.ternparagliding.mezulla.pairing.TeamLink.parse(uri)?.let { team ->
            Log.i(TAG, "Deep link: join team '${team.name}'")
            val store = ViewModelProvider(this)[MapStore::class.java]
            store.dispatch(
                com.ternparagliding.redux.MapAction.SetTeam(
                    team.name, com.ternparagliding.mezulla.pairing.TeamLink.encode(team), "manual",
                ),
            )
            android.widget.Toast.makeText(this@TernParaglidingActivity, "Joined team: ${team.name}", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val link = TernPairLink.parse(uri) ?: return

        Log.i(TAG, "Deep link received: node=${link.nodeIdHex}")

        if (hasBlePermissions()) {
            // Close any stale persistent connection from a prior session
            // BEFORE starting the new pair flow. Without this, the prior
            // connection (started by initialize() at activity onCreate
            // from the saved pair record) can race ahead and trigger an
            // SMP pair request using the *previous* PIN, blowing the
            // new pair before it begins. Pairing always wants a fresh
            // GATT slot.
            connectionManager.stopActiveConnection()
            pairingOrchestrator.handlePairLink(link)
            Log.i(TAG, "Deep link handled by pairing orchestrator")
        } else {
            pendingPairLink = link
            requestBlePermissions()
        }
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blePermissionLauncher.launch(arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ))
        }
    }
}
