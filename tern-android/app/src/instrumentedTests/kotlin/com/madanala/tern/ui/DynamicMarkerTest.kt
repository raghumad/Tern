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
import org.junit.Rule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity

@RunWith(AndroidJUnit4::class)
class DynamicMarkerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testCreateBitmapFromComposable() {
        // GIVEN a context from the activity
        val context = composeTestRule.activity
        val parent = context.window.decorView as ViewGroup
        val width = 100
        val height = 100

        // WHEN creating a bitmap
        var bitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        
        // ViewToBitmap must be called on main thread (suspend)
        composeTestRule.runOnUiThread {
            MainScope().launch {
                try {
                    bitmap = ViewToBitmap.createBitmapFromComposable(
                        parentView = parent,
                        width = width,
                        height = height,
                        lifecycleOwner = composeTestRule.activity, // Explicitly pass lifecycle owner
                        viewModelStoreOwner = composeTestRule.activity, // Explicitly pass view model store owner
                        savedStateRegistryOwner = composeTestRule.activity // Explicitly pass saved state registry owner
                    ) {
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
