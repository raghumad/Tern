package com.madanala.tern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import org.osmdroid.config.Configuration

class TernParaglidingActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "tern_settings_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Configuration.getInstance().load(applicationContext, getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            TernTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TernMapScreen()
                }
            }
        }
    }
}
