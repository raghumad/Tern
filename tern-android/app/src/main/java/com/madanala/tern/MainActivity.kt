package com.madanala.tern

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.madanala.tern.ui.screens.MAP_VIEW_TERRAIN
import com.madanala.tern.ui.screens.TernMapScreen
import com.madanala.tern.ui.theme.TernTheme
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "tern_settings_prefs"
        private const val PREF_MAP_VIEW = "pref_map_view"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            TernTheme {
                val sharedPreferences = remember { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
                var mapStyle by remember {
                    mutableStateOf(sharedPreferences.getInt(PREF_MAP_VIEW, MAP_VIEW_TERRAIN))
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TernMapScreen(
                        mapStyle = mapStyle,
                        updateMapStyle = { newStyle ->
                            mapStyle = newStyle
                            with(sharedPreferences.edit()) {
                                putInt(PREF_MAP_VIEW, newStyle)
                                apply()
                            }
                        }
                    )
                }
            }
        }
    }
}
