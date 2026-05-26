package com.ternparagliding

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ternparagliding.mezulla.pairing.PairingOrchestrator
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        CacheManager.initialize(applicationContext)

        Configuration.getInstance().load(applicationContext, getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        // Handle deep link if the activity was launched via tern:// URL
        intent?.let { handleDeepLink(it) }

        setContent {
            TernTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TernMapScreen()
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
        val handled = pairingOrchestrator.handleIntent(intent)
        if (handled) {
            Log.i(TAG, "Deep link handled by pairing orchestrator")
        }
    }
}
