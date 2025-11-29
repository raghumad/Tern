package com.madanala.tern.ui

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.ui.components.WindGaugeMarker
import com.madanala.tern.utils.ViewToBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

import android.view.ViewGroup
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DynamicMarkerTest {

    @get:org.junit.Rule
    val composeTestRule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun testCreateBitmapFromComposable() {
        // GIVEN a context from the activity
        val context = composeTestRule.activity
        val parent = context.findViewById<ViewGroup>(android.R.id.content)
        val width = 100
        val height = 100

        // WHEN creating a bitmap
        var bitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        
        // ViewToBitmap must be called on main thread (suspend)
        composeTestRule.runOnUiThread {
            MainScope().launch {
                try {
                    bitmap = ViewToBitmap.createBitmapFromComposable(parent, width, height) {
                        WindGaugeMarker(speed = 10.0, direction = 180.0)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for result
        latch.await(5, TimeUnit.SECONDS)

        // THEN the bitmap should be created with correct dimensions
        assertNotNull("Bitmap should not be null", bitmap)
        assertEquals("Width should match", width, bitmap?.width)
        assertEquals("Height should match", height, bitmap?.height)
        
        // Verify it's not empty (check a pixel or config)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap?.config)
    }
}
