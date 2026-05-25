package com.madanala.tern.utils

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Captures screenshots at a fixed interval during a test, producing
 * numbered frames that ffmpeg can stitch into a video on the host.
 *
 * Usage:
 *   val capture = FrameCaptureHelper("my_test", intervalMs = 500)
 *   capture.start()
 *   // ... run test scenario ...
 *   capture.stop()
 *   // frames are at /sdcard/tern-tests/my_test/frame_0001.png etc.
 *
 * After the test, on the host:
 *   adb pull /sdcard/tern-tests/my_test/ frames/
 *   ffmpeg -framerate 2 -i frames/frame_%04d.png -c:v libx264 -pix_fmt yuv420p test.mp4
 */
class FrameCaptureHelper(
    private val testName: String,
    private val intervalMs: Long = 500L,
) {
    private val tag = "FrameCapture"
    private val outputDir = "/sdcard/tern-tests/$testName"
    private var captureJob: Job? = null
    private var frameCount = 0

    fun start(scope: CoroutineScope) {
        if (captureJob != null) return

        frameCount = 0
        try {
            Runtime.getRuntime().exec(arrayOf("mkdir", "-p", outputDir)).waitFor()
        } catch (e: Exception) {
            Log.w(tag, "Could not create $outputDir", e)
        }

        captureJob = scope.launch(Dispatchers.IO) {
            val device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()
            )
            while (isActive) {
                frameCount++
                val fileName = String.format("frame_%04d.png", frameCount)
                val file = File(outputDir, fileName)
                try {
                    device.takeScreenshot(file)
                } catch (e: Exception) {
                    Log.w(tag, "Frame $frameCount failed: ${e.message}")
                }
                delay(intervalMs)
            }
        }
        Log.i(tag, "Frame capture started: $outputDir @ ${intervalMs}ms intervals")
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        Log.i(tag, "Frame capture stopped: $frameCount frames in $outputDir")
        Log.i(tag, "To create video on host: adb pull $outputDir/ frames/ && ffmpeg -framerate ${1000/intervalMs} -i frames/frame_%04d.png -c:v libx264 -pix_fmt yuv420p $testName.mp4")
    }
}
