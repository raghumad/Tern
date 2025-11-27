package com.madanala.tern.utils

import android.graphics.Bitmap
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ScreenshotHelper {

    fun takeScreenshot(name: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots")
        if (!path.exists()) {
            path.mkdirs()
        }

        val filename = "${name}_${System.currentTimeMillis()}.png"
        val file = File(path, filename)

        try {
            device.takeScreenshot(file)
            println("Screenshot saved: ${file.absolutePath}")
        } catch (e: IOException) {
            e.printStackTrace()
            println("Failed to save screenshot: ${e.message}")
        }
    }
}
