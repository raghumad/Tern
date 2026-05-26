package com.ternparagliding.utils

import android.util.Log
import java.io.File

/**
 * Records the emulator/device screen to MP4 for BDD test evidence.
 *
 * Uses `screenrecord` via shell, which is available on API 19+ and on
 * all emulator images we use. Videos are saved to
 * `/sdcard/tern-tests/{testName}.mp4` and can be pulled after the
 * test run with:
 *
 *     adb pull /sdcard/tern-tests/
 *
 * Android's screenrecord has a hard 180-second limit per recording.
 * If a test runs longer, call [splitRecording] to start a new segment.
 *
 * This helper is opt-in. Tests that don't need video evidence skip it
 * entirely -- no overhead.
 */
object VideoHelper {

    private const val TAG = "VideoHelper"
    private const val OUTPUT_DIR = "/sdcard/tern-tests"
    private const val MAX_DURATION_SECONDS = 180

    private var recordingProcess: Process? = null
    private var currentTestName: String? = null
    private var segmentIndex: Int = 0

    /**
     * Start recording the screen. The video file will be at
     * `/sdcard/tern-tests/{testName}.mp4` (or `{testName}_seg{N}.mp4`
     * for subsequent segments).
     *
     * No-op if already recording. Call [stopRecording] before starting
     * a new recording.
     */
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

    /**
     * Stop the current recording. The MP4 file is finalized on disk.
     */
    fun stopRecording() {
        val process = recordingProcess ?: return
        try {
            // screenrecord responds to SIGINT by finalizing the file
            process.destroy()
            // Give it a moment to flush
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping screenrecord", e)
        }
        recordingProcess = null
        currentTestName = null
        Log.i(TAG, "Recording stopped")
    }

    /**
     * Split the current recording into a new segment. Use this when a
     * test approaches the 180-second limit.
     */
    fun splitRecording() {
        val testName = currentTestName ?: return
        stopRecording()
        currentTestName = testName
        segmentIndex++
        startSegment()
    }

    /**
     * Whether a recording is currently in progress.
     */
    val isRecording: Boolean
        get() = recordingProcess != null

    private fun startSegment() {
        val testName = currentTestName ?: return
        val suffix = if (segmentIndex == 0) "" else "_seg$segmentIndex"
        val outputPath = "$OUTPUT_DIR/${testName}${suffix}.mp4"

        try {
            val cmd = arrayOf(
                "screenrecord",
                "--time-limit", MAX_DURATION_SECONDS.toString(),
                outputPath,
            )
            recordingProcess = Runtime.getRuntime().exec(cmd)
            Log.i(TAG, "Recording started: $outputPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screenrecord", e)
            recordingProcess = null
        }
    }

    private fun ensureOutputDir() {
        try {
            Runtime.getRuntime().exec(arrayOf("mkdir", "-p", OUTPUT_DIR)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Could not create $OUTPUT_DIR", e)
        }
    }
}
