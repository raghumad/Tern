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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.ternparagliding.mezulla.MezullaConnectionManager
import com.ternparagliding.mezulla.pairing.PairingOrchestrator
import com.ternparagliding.mezulla.pairing.TernPairLink
import com.ternparagliding.redux.MapStore
import com.ternparagliding.ui.screens.TernMapScreen
import com.ternparagliding.ui.theme.TernTheme
import com.ternparagliding.utils.CacheManager
import org.osmdroid.config.Configuration

class TernParaglidingActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TernParaglidingActivity"
        private const val PREFS_NAME = "tern_settings_prefs"
    }

    val pairingOrchestrator: PairingOrchestrator by lazy {
        PairingOrchestrator(applicationContext)
    }

    private val connectionManager: MezullaConnectionManager by lazy {
        MezullaConnectionManager(applicationContext, pairingOrchestrator)
    }

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
        val mapStore = ViewModelProvider(this)[MapStore::class.java]
        connectionManager.initialize(mapStore)

        intent?.let { handleDeepLink(it) }

        setContent {
            TernTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
        val link = TernPairLink.parse(uri) ?: return

        Log.i(TAG, "Deep link received: node=${link.nodeIdHex}")

        if (hasBlePermissions()) {
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
