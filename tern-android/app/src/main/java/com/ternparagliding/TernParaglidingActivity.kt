package com.ternparagliding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ternparagliding.ui.screens.TernMapScreen
import com.ternparagliding.ui.theme.TernTheme
import com.ternparagliding.utils.CacheManager
import org.osmdroid.config.Configuration

class TernParaglidingActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "tern_settings_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Keep screen on for flight computer visibility and process priority
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize singleton cache manager for aviation-grade resilience
        CacheManager.initialize(applicationContext)

        Configuration.getInstance().load(applicationContext, getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            TernTheme(darkTheme = true) { // Aviation-Grade: Force Aero Stealth (Dark) for visual consistency & cockpit readability
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TernMapScreen()
                }
            }
        }
    }
}
