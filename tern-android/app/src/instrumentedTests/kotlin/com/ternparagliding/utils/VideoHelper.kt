package com.ternparagliding.utils

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import java.io.File
import java.io.FileInputStream

/**
 * Records the emulator/device screen to MP4 for BDD test evidence.
 *
 * Videos are recorded to a temp path on the device via `screenrecord`,
 * then copied into [TestStorage] when recording stops. TestStorage is
 * the same mechanism the BDD screenshots use — Gradle's managed device
 * infrastructure collects it automatically, so videos end up in
 * `build/outputs/managed_device_android_test_additional_output/` and
 * then in `build/reports/bdd-report/` alongside the screenshots.
 *
 * No manual `adb pull` needed.
 */
object VideoHelper {

    private const val TAG = "VideoHelper"
    private const val TEMP_DIR = "/sdcard/tern-tests"
    private const val MAX_DURATION_SECONDS = 180

    private var recordingProcess: Process? = null
    private var currentTestName: String? = null
    private var currentTempPath: String? = null
    private var segmentIndex: Int = 0
    private val testStorage = try { TestStorage() } catch (_: Exception) { null }

    fun startRecording(testName: String) {
        if (recordingProcess != null) {
            Log.w(TAG, "Already recording -- ignoring startRecording($testName)")
            return
        }

        currentTestName = testName
        segmentIndex = 0

        ensureOutputDir()
        startSegment()
    }

    fun stopRecording() {
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

        // Copy the finished recording into TestStorage so Gradle collects it
        if (tempPath != null && testName != null) {
            copyToTestStorage(tempPath, testName)
        }

        currentTestName = null
        currentTempPath = null
        Log.i(TAG, "Recording stopped")
    }

    fun splitRecording() {
        val testName = currentTestName ?: return
        stopRecording()
        currentTestName = testName
        segmentIndex++
        startSegment()
    }

    val isRecording: Boolean
        get() = recordingProcess != null

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
