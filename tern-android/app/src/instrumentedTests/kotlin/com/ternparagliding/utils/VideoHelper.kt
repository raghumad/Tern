package com.ternparagliding.utils

import android.util.Log
import androidx.test.services.storage.TestStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileInputStream

/**
 * Records the emulator/device screen to MP4 for BDD test evidence.
 *
 * Primary path: `screenrecord` via shell. If the device doesn't
 * support it (AOSP ATD images), falls back to [FrameCaptureHelper]
 * which takes screenshots at intervals.
 *
 * All output goes through [TestStorage] so Gradle's managed device
 * infrastructure collects it automatically.
 */
object VideoHelper {

    private const val TAG = "VideoHelper"
    private const val TEMP_DIR = "/sdcard/tern-tests"
    private const val MAX_DURATION_SECONDS = 180
    private const val SCREENRECORD_PROBE_MS = 1500L

    private var recordingProcess: Process? = null
    private var currentTestName: String? = null
    private var currentTempPath: String? = null
    private var segmentIndex: Int = 0
    private val testStorage = try { TestStorage() } catch (_: Exception) { null }

    // Fallback
    private var frameCaptureHelper: FrameCaptureHelper? = null
    private var fallbackScope: CoroutineScope? = null
    val isUsingFallback: Boolean get() = frameCaptureHelper != null

    fun startRecording(testName: String) {
        if (recordingProcess != null || frameCaptureHelper != null) {
            Log.w(TAG, "Already recording -- ignoring startRecording($testName)")
            return
        }

        currentTestName = testName
        segmentIndex = 0

        ensureOutputDir()
        startSegment()

        // Probe: does screenrecord actually produce output on this device?
        if (recordingProcess != null) {
            try { Thread.sleep(SCREENRECORD_PROBE_MS) } catch (_: InterruptedException) {}
            val file = currentTempPath?.let { File(it) }
            if (file == null || !file.exists() || file.length() == 0L) {
                Log.w(TAG, "screenrecord not producing output — falling back to FrameCaptureHelper")
                recordingProcess?.destroy()
                recordingProcess = null
                activateFallback(testName)
            }
        } else {
            activateFallback(testName)
        }
    }

    fun stopRecording() {
        // Fallback path
        val helper = frameCaptureHelper
        if (helper != null) {
            val frames = helper.stop()
            fallbackScope?.cancel()
            fallbackScope = null
            frameCaptureHelper = null
            Log.i(TAG, "Frame capture stopped: $frames frames")
            currentTestName = null
            currentTempPath = null
            return
        }

        // screenrecord path
        val process = recordingProcess ?: return
        val tempPath = currentTempPath
        val testName = currentTestName
        try {
            process.destroy()
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping screenrecord", e)
        }
        recordingProcess = null

        if (tempPath != null && testName != null) {
            copyToTestStorage(tempPath, testName)
        }

        currentTestName = null
        currentTempPath = null
        Log.i(TAG, "Recording stopped")
    }

    fun splitRecording() {
        if (frameCaptureHelper != null) return // no-op for fallback
        val testName = currentTestName ?: return
        stopRecording()
        currentTestName = testName
        segmentIndex++
        startSegment()
    }

    val isRecording: Boolean
        get() = recordingProcess != null || frameCaptureHelper != null

    private fun activateFallback(testName: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        fallbackScope = scope
        val helper = FrameCaptureHelper(testName, intervalMs = 500L)
        frameCaptureHelper = helper
        helper.start(scope)
        Log.i(TAG, "Fallback: FrameCaptureHelper started for $testName @ 500ms")
    }

    private fun startSegment() {
        val testName = currentTestName ?: return
        val suffix = if (segmentIndex == 0) "" else "_seg$segmentIndex"
        val tempPath = "$TEMP_DIR/${testName}${suffix}.mp4"
        currentTempPath = tempPath

        try {
            recordingProcess = Runtime.getRuntime().exec(arrayOf(
                "screenrecord",
                "--time-limit", MAX_DURATION_SECONDS.toString(),
                tempPath,
            ))
            Log.i(TAG, "Recording started: $tempPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screenrecord", e)
            recordingProcess = null
        }
    }

    private fun copyToTestStorage(tempPath: String, testName: String) {
        val storage = testStorage ?: return
        val file = File(tempPath)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Recording file missing or empty: $tempPath")
            return
        }
        try {
            val suffix = if (segmentIndex == 0) "" else "_seg$segmentIndex"
            val outputName = "${testName}${suffix}.mp4"
            storage.openOutputFile(outputName).use { out ->
                FileInputStream(file).use { inp -> inp.copyTo(out) }
            }
            Log.i(TAG, "Video copied to TestStorage: $outputName (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy video to TestStorage: ${e.message}")
        }
    }

    private fun ensureOutputDir() {
        try {
            Runtime.getRuntime().exec(arrayOf("mkdir", "-p", TEMP_DIR)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Could not create $TEMP_DIR", e)
        }
    }
}
