package com.ternparagliding.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures screenshots at a fixed interval during a test, producing
 * numbered frames that ffmpeg can stitch into a video on the host.
 *
 * Uses [ReportGenerator.captureScreenshot] which is the same mechanism
 * the BDD framework uses for step screenshots — known to work on ATD
 * managed devices where `screenrecord` fails.
 *
 * Usage:
 *   val capture = FrameCaptureHelper("my_test", intervalMs = 500)
 *   capture.start(scope)
 *   // ... run test scenario ...
 *   val frameCount = capture.stop()
 *
 * After the test, on the host:
 *   ./scripts/make-test-video.sh my_test
 */
class FrameCaptureHelper(
    private val testName: String,
    private val intervalMs: Long = 500L,
) {
    private val tag = "FrameCapture"
    private var captureJob: Job? = null
    private var frameCount = 0

    fun start(scope: CoroutineScope) {
        if (captureJob != null) return
        frameCount = 0

        captureJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                frameCount++
                val frameName = String.format("${testName}_frame_%04d", frameCount)
                try {
                    ReportGenerator.captureScreenshot(frameName)
                } catch (e: Exception) {
                    Log.w(tag, "Frame $frameCount failed: ${e.message}")
                }
                delay(intervalMs)
            }
        }
        Log.i(tag, "Frame capture started @ ${intervalMs}ms intervals")
    }

    fun stop(): Int {
        captureJob?.cancel()
        captureJob = null
        Log.i(tag, "Frame capture stopped: $frameCount frames")
        return frameCount
    }
}
